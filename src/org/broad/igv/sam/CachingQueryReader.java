/*
 * Copyright (c) 2007-2011 by The Broad Institute, Inc. and the Massachusetts Institute of
 * Technology.  All Rights Reserved.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 *
 * THE SOFTWARE IS PROVIDED "AS IS." THE BROAD AND MIT MAKE NO REPRESENTATIONS OR
 * WARRANTES OF ANY KIND CONCERNING THE SOFTWARE, EXPRESS OR IMPLIED, INCLUDING,
 * WITHOUT LIMITATION, WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE, NONINFRINGEMENT, OR THE ABSENCE OF LATENT OR OTHER DEFECTS, WHETHER
 * OR NOT DISCOVERABLE.  IN NO EVENT SHALL THE BROAD OR MIT, OR THEIR RESPECTIVE
 * TRUSTEES, DIRECTORS, OFFICERS, EMPLOYEES, AND AFFILIATES BE LIABLE FOR ANY DAMAGES
 * OF ANY KIND, INCLUDING, WITHOUT LIMITATION, INCIDENTAL OR CONSEQUENTIAL DAMAGES,
 * ECONOMIC DAMAGES OR INJURY TO PROPERTY AND LOST PROFITS, REGARDLESS OF WHETHER
 * THE BROAD OR MIT SHALL BE ADVISED, SHALL HAVE OTHER REASON TO KNOW, OR IN FACT
 * SHALL KNOW OF THE POSSIBILITY OF THE FOREGOING.
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.broad.igv.sam;


import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.util.CloseableIterator;
import org.apache.log4j.Logger;
import org.broad.igv.PreferenceManager;
import org.broad.igv.exceptions.DataLoadException;
import org.broad.igv.sam.reader.AlignmentQueryReader;
import org.broad.igv.sam.reader.ReadGroupFilter;
import org.broad.igv.ui.IGVMainFrame;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.LRUCache;
import org.broad.igv.util.RuntimeUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * A wrapper for an AlignmentQueryReader that caches query results
 *
 * @author jrobinso
 */
public class CachingQueryReader {

    private static Logger log = Logger.getLogger(CachingQueryReader.class);

    //private static final int LOW_MEMORY_THRESHOLD = 150000000;
    private static final int KB = 1000;
    private static final int MITOCHONDRIA_TILE_SIZE = 1000;
    private static int DEFAULT_TILE_SIZE = 16 * KB;
    private static int MAX_TILE_COUNT = 4;
    private static Set<WeakReference<CachingQueryReader>> activeReaders = Collections.synchronizedSet(new HashSet());
    private PairedEndStats peStats;

    private static void cancelReaders() {
        for (WeakReference<CachingQueryReader> readerRef : activeReaders) {
            CachingQueryReader reader = readerRef.get();
            if (reader != null) {
                reader.cancel = true;
            }
        }
        log.debug("Readers canceled");
        activeReaders.clear();
    }


    private String cachedChr = "";
    private int tileSize = DEFAULT_TILE_SIZE;
    private AlignmentQueryReader reader;
    private boolean cancel = false;
    private LRUCache<Integer, AlignmentTile> cache;

    public CachingQueryReader(AlignmentQueryReader reader) {
        this.reader = reader;
        cache = new LRUCache(this, MAX_TILE_COUNT);
        float fvw = PreferenceManager.getInstance().getAsFloat(PreferenceManager.SAM_MAX_VISIBLE_RANGE);
        tileSize = Math.min(DEFAULT_TILE_SIZE, (int) (fvw * KB));
    }

    public AlignmentQueryReader getWrappedReader() {
        return reader;
    }

    public void close() throws IOException {
        reader.close();
    }

    public Set<String> getSequenceNames() {
        return reader.getSequenceNames();
    }

    public SAMFileHeader getHeader() throws IOException {
        return reader.getHeader();
    }

    public CloseableIterator<Alignment> iterator() {
        return reader.iterator();
    }

    public boolean hasIndex() {
        return reader.hasIndex();
    }

    public CloseableIterator<Alignment> query(String sequence, int start, int end, List<AlignmentCounts> counts, int maxReadDepth) {

        // Get the tiles covering this interval
        int startTile = (start + 1) / getTileSize(sequence);
        int endTile = end / getTileSize(sequence);    // <= inclusive

        // Be a bit conservative with maxReadDepth (get a few more reads than we think neccessary)
        int readDepthPlus = (int) (1.1 * maxReadDepth);

        List<AlignmentTile> tiles = getTiles(sequence, startTile, endTile, readDepthPlus);
        if (tiles.size() == 0) {
            return EmptyAlignmentIterator.getInstance();
        }

        // Count total # of records
        int recordCount = tiles.get(0).getOverlappingRecords().size();
        for (AlignmentTile t : tiles) {
            recordCount += t.getContainedRecords().size();
        }

        List<Alignment> alignments = new ArrayList(recordCount);
        alignments.addAll(tiles.get(0).getOverlappingRecords());
        for (AlignmentTile t : tiles) {
            alignments.addAll(t.getContainedRecords());
            counts.add(t.getCounts());
        }

        if (alignments.size() > 1000) {
            peStats = PairedEndStats.compute(alignments.iterator(), .1, 99.9);
        }

        return new TiledIterator(start, end, alignments);
    }

    public List<AlignmentTile> getTiles(String seq, int startTile, int endTile, int maxReadDepth) {

        if (!seq.equals(cachedChr)) {
            cache.clear();
            cachedChr = seq;
        }

        List<AlignmentTile> tiles = new ArrayList(endTile - startTile + 1);
        List<AlignmentTile> tilesToLoad = new ArrayList(endTile - startTile + 1);

        int tileSize = getTileSize(seq);
        for (int t = startTile; t <= endTile; t++) {
            AlignmentTile tile = cache.get(t);

            if (tile == null) {
                int start = t * tileSize;
                int end = start + tileSize;
                tile = new AlignmentTile(seq, t, start, end, maxReadDepth);
            }

            tiles.add(tile);

            // The current tile is loaded,  load any preceding tiles we have pending and clear "to load" list
            if (tile.isLoaded()) {
                if (tilesToLoad.size() > 0) {
                    boolean success = loadTiles(seq, tilesToLoad);
                    if (!success) {
                        // Loading was canceled, return what we have
                        return tiles;
                    }
                }
                tilesToLoad.clear();
            } else {
                tilesToLoad.add(tile);
            }
        }

        if (tilesToLoad.size() > 0) {
            loadTiles(seq, tilesToLoad);
        }

        return tiles;
    }

    /**
     * Load alignments for the list of tiles
     *
     * @param chr
     * @param tiles
     * @return true if successful,  false if canceled.
     */
    private boolean loadTiles(String chr, List<AlignmentTile> tiles) {

        assert (tiles.size() > 0);

        boolean filterFailedReads = PreferenceManager.getInstance().getAsBoolean(PreferenceManager.SAM_FILTER_FAILED_READS);
        ReadGroupFilter filter = ReadGroupFilter.getFilter();
        boolean showDuplicates = PreferenceManager.getInstance().getAsBoolean(PreferenceManager.SAM_SHOW_DUPLICATES);
        int qualityThreshold = PreferenceManager.getInstance().getAsInt(PreferenceManager.SAM_QUALITY_THRESHOLD);

        //maxReadCount = PreferenceManager.getInstance().getAsInt(PreferenceManager.SAM_MAX_READS);

        if (log.isDebugEnabled()) {
            int first = tiles.get(0).getTileNumber();
            int end = tiles.get(tiles.size() - 1).getTileNumber();
            log.debug("Loading tiles: " + first + "-" + end);
        }

        int start = tiles.get(0).start;
        int end = tiles.get(tiles.size() - 1).end;


        CloseableIterator<Alignment> iter = null;

        //log.debug("Loading : " + start + " - " + end);
        int alignmentCount = 0;
        try {
            //activeReaders.add(this);
            iter = reader.query(chr, start, end, false);

            Map<String, Alignment> mappedMates = new HashMap(1000);
            Map<String, Alignment> unmappedMates = new HashMap(1000);

            int tileSize = getTileSize(chr);
            while (iter != null && iter.hasNext()) {

                if (cancel) {
                    return false;
                }

                Alignment record = iter.next();

                String readName = record.getReadName();
                if (record.isPaired()) {
                    if (record.isMapped()) {
                        if (!record.getMate().isMapped()) {
                            // record is mapped, mate is not
                            Alignment mate = unmappedMates.get(readName);
                            if (mate == null) {
                                mappedMates.put(readName, record);
                            } else {
                                record.setMateSequence(mate.getReadSequence());
                                unmappedMates.remove(readName);
                                mappedMates.remove(readName);
                            }

                        }
                    } else if (record.getMate().isMapped()) {
                        // record not mapped, mate is
                        Alignment mappedMate = mappedMates.get(readName);
                        if (mappedMate == null) {
                            unmappedMates.put(readName, record);
                        } else {
                            mappedMate.setMateSequence(record.getReadSequence());
                            unmappedMates.remove(readName);
                            mappedMates.remove(readName);
                        }
                    }
                }


                if (!record.isMapped() || (!showDuplicates && record.isDuplicate()) ||
                        (filterFailedReads && record.isVendorFailedRead()) ||
                        record.getMappingQuality() < qualityThreshold ||
                        (filter != null && filter.filterAlignment(record))) {
                    continue;
                }

                // Range of tile indeces that this alignment contributes to.
                int aStart = record.getAlignmentStart();
                int aEnd = record.getEnd();
                int idx0 = Math.max(0, (aStart - start) / tileSize);
                int idx1 = Math.min(tiles.size() - 1, (aEnd - start) / tileSize);

                // Loop over tiles this read overlaps
                for (int i = idx0; i <= idx1; i++) {
                    AlignmentTile t = tiles.get(i);
                    t.addRecord(record);
                }

                alignmentCount++;
                if (alignmentCount % 1000 == 0) {
                    if (cancel) return false;
                    IGVMainFrame.getInstance().setStatusBarMessage("Reads loaded: " + alignmentCount);
                    if (checkMemory() == false) {
                        cancelReaders();
                        return false;        // <=  TODO need to cancel all readers
                    }
                }
            }

            // Clean up any remaining unmapped mate seqeunces
            for (Alignment mappedMate : mappedMates.values()) {
                Alignment mate = unmappedMates.get(mappedMate.getReadName());
                if (mate != null) {
                    mappedMate.setMateSequence(mate.getReadSequence());
                }
            }
            mappedMates = null;
            unmappedMates = null;

            for (AlignmentTile t : tiles) {
                t.setLoaded(true);
                cache.put(t.getTileNumber(), t);
            }

            return true;

        } catch (Exception
                e) {
            log.error("Error loading alignment data", e);
            throw new DataLoadException("", "Error: " + e.toString());
        }

        finally {
            // reset cancel flag.  It doesn't matter how we got here,  the read is complete and this flag is reset
            // for the next time
            cancel = false;
            // activeReaders.remove(this);
            if (iter != null) {
                iter.close();
            }
            IGVMainFrame.getInstance().resetStatusMessage();
        }
    }


    private static synchronized boolean checkMemory() {
        if (RuntimeUtils.getAvailableMemoryFraction() < 0.2) {
            LRUCache.clearCaches();
            System.gc();
            if (RuntimeUtils.getAvailableMemoryFraction() < 0.2) {
                String msg = "Memory is low, reading terminating.";
                MessageUtils.showMessage(msg);
                return false;
            }

        }
        return true;
    }

    /**
     * @return the tileSize
     */
    public int getTileSize(String chr) {
        if (chr.equals("M") || chr.equals("chrM") || chr.equals("MT") || chr.equals("chrMT")) {
            return MITOCHONDRIA_TILE_SIZE;
        } else {
            return tileSize;
        }
    }

    public void clearCache() {
        if (cache != null) cache.clear();
    }

    public PairedEndStats getPeStats() {
        return peStats;
    }


    public class TiledIterator implements CloseableIterator<Alignment> {

        Iterator<Alignment> currentSamIterator;
        int end;
        Alignment nextRecord;
        int start;
        List<Alignment> alignments;

        TiledIterator(int start, int end, List<Alignment> alignments) {
            this.alignments = alignments;
            this.start = start;
            this.end = end;
            currentSamIterator = alignments.iterator();
            advanceToFirstRecord();
        }

        public void close() {
            // No-op
        }

        public boolean hasNext() {
            return nextRecord != null;
        }

        public Alignment next() {
            Alignment ret = nextRecord;

            advanceToNextRecord();

            return ret;
        }

        public void remove() {
            // ignored
        }

        private void advanceToFirstRecord() {
            advanceToNextRecord();
        }

        private void advanceToNextRecord() {
            advance();

            while ((nextRecord != null) && (nextRecord.getEnd() < start)) {
                advance();
            }
        }

        private void advance() {
            if (currentSamIterator.hasNext()) {
                nextRecord = currentSamIterator.next();
                if (nextRecord.getAlignmentStart() > end) {
                    nextRecord = null;
                }
            } else {
                nextRecord = null;
            }
        }


    }

    /**
     * Caches alignments and counts for the coverage plot.
     * <p/>
     * Notes:
     * A "bucket" is a virtual container holding all the alignments with identical start position.  The concept
     * is introduced to control the # of alignments we hold in memory for deep coverage regions.  In practice,
     * little or no information is added by displaying more than ~50X coverage.  For an average alignment length L
     * and coverage deptgh D we do not need to store more than D/L alignments at any given start position.
     */

    public static class AlignmentTile {

        private boolean loaded = false;
        private List<Alignment> containedRecords;
        private int end;
        private List<Alignment> overlappingRecords;
        private int start;
        private int tileNumber;
        private AlignmentCounts counts;

        int maxDepth;
        int e1;
        int depthCount;

        /**
         * Maximum # of alignments to load with a given start position
         */
        //private int lastStart = -1;
        //private int bucketCount = 0;
        private HashMap<String, Alignment> currentBucket;
        private List<Alignment> pairedBucket;
        private Set<String> unmappedPairs;
        private Set<String> currentUnmappedPairs;

        private static final Random RAND = new Random(System.currentTimeMillis());

        AlignmentTile(String chr, int tileNumber, int start, int end, int maxDepth) {
            this.tileNumber = tileNumber;
            this.start = start;
            this.end = end;
            containedRecords = new ArrayList(16000);
            overlappingRecords = new ArrayList();
            this.counts = new AlignmentCounts(chr, start, end);

            this.maxDepth = maxDepth;
            e1 = -1;
            depthCount = 0;

            // TODO -- compute this value from the data
            //maxBucketSize = (maxDepth / 10) + 1;
            currentBucket = new HashMap(5 * maxDepth);
            pairedBucket = new ArrayList(maxDepth);
            unmappedPairs = new HashSet(5 * maxDepth);
            currentUnmappedPairs = new HashSet(maxDepth);
        }

        public int getTileNumber() {
            return tileNumber;
        }


        public int getStart() {
            return start;
        }

        public void setStart(int start) {
            this.start = start;
        }


        public void addRecord(Alignment record) {
            if (record.getStart() > e1) {
                emptyBucket();
                e1 = record.getEnd();
                depthCount = 0;
            } else {
                e1 = Math.min(e1, record.getEnd());
                depthCount++;
            }

            final String readName = record.getReadName();
            if (unmappedPairs.contains(readName)) {
                pairedBucket.add(record);
                unmappedPairs.remove(readName);
            } else {
                if (currentBucket.containsKey(readName)) {
                    PairedAlignment pa = new PairedAlignment(currentBucket.get(readName));
                    pa.setSecondAlignment(record);
                    currentBucket.put(readName, pa);
                } else {
                    currentBucket.put(readName, record);
                }

            }
            counts.incCounts(record);
        }


        private void emptyBucket() {

            List<Alignment> sampledRecords = sampleCurrentBucket();
            for (Alignment alignment : sampledRecords) {
                int aStart = alignment.getAlignmentStart();
                int aEnd = alignment.getEnd();
                if ((aStart >= start) && (aStart < end)) {
                    containedRecords.add(alignment);
                } else if ((aEnd >= start) && (aStart < start)) {
                    overlappingRecords.add(alignment);
                }
            }
            pairedBucket.clear();
            currentBucket.clear();
        }


        /**
         * Sample the current bucket of alignments to achieve the desired depth.
         *
         * @return
         */
        private List<Alignment> sampleCurrentBucket() {

            List sampledList = new ArrayList(maxDepth);

            sampledList.addAll(pairedBucket);

            if (pairedBucket.size() + 2 * currentBucket.size() < maxDepth) {
                sampledList.addAll(currentBucket.values());
            } else {

                ArrayList<Alignment> currentBucketList = new ArrayList(currentBucket.values());
                while (sampledList.size() < maxDepth && currentBucketList.size() > 0) {
                    Alignment a = currentBucketList.remove(RAND.nextInt(currentBucketList.size()));
                    if (a instanceof PairedAlignment) {
                        sampledList.add(((PairedAlignment) a).getFirstAlignment());
                        sampledList.add(((PairedAlignment) a).getSecondAlignment());
                    } else {
                        if (a.isPaired() && a.isProperPair()) {
                            unmappedPairs.add(a.getReadName());
                        }
                        sampledList.add(a);
                    }
                }
            }


            Collections.sort(sampledList, new Comparator<Alignment>() {
                public int compare(Alignment alignment, Alignment alignment1) {
                    return alignment.getStart() - alignment1.getStart();
                }
            });

            return sampledList;

        }


        public List<Alignment> getContainedRecords() {
            return containedRecords;
        }


        public List<Alignment> getOverlappingRecords() {
            return overlappingRecords;
        }

        public boolean isLoaded() {
            return loaded;
        }

        public void setLoaded(boolean loaded) {
            this.loaded = loaded;

            // Empty any remaining alignments in the current bucket
            emptyBucket();
            unmappedPairs = null;
            currentBucket = null;
            pairedBucket = null;

        }

        public AlignmentCounts getCounts() {
            return counts;
        }
    }


}

