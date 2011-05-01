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
* Genome.java
*
* Created on November 9, 2007, 9:05 AM
*
* To change this template, choose Tools | Template Manager
* and open the template in the editor.
*/
package org.broad.igv.feature.genome;


import org.apache.log4j.Logger;
import org.broad.igv.Globals;
import org.broad.igv.feature.*;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.ResourceLocator;
import org.broad.tribble.readers.AsciiLineReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Simple model of a genome
 */
public class Genome {

    private static Logger log = Logger.getLogger(Genome.class);

    private Map<String, String> chrAliasTable;

    private String id;
    private String formatVersion;
    private LinkedHashMap<String, Chromosome> chromosomeMap;
    private List<String> chromosomeNames;
    private long length = -1;
    private String annotationURL;
    private Map<String, Long> cumulativeOffsets = new HashMap();
    public static final int MAX_WHOLE_GENOME = 10000;

    GeneManager geneManager;
    GenomeDescriptor descriptor;

    public Genome(GenomeDescriptor descriptor) {
        this.descriptor = descriptor;
        this.id = descriptor.getId();
        initAnnotationURL();
        chrAliasTable = new HashMap();
    }


    private void initAnnotationURL() {
        if (id != null) {
            //if (ucscGenomes.contains(id)) {
            //    annotationURL = "http://genome.ucsc.edu/cgi-bin/hgGene?hgg_gene=$$&db=" + id;
            //}
        }
    }


    public String getChromosomeAlias(String str) {
        if (chrAliasTable == null) {
            return str;
        } else {
            if (chrAliasTable.containsKey(str)) {
                return chrAliasTable.get(str);
            }
        }
        return str;
    }

    public void loadUserDefinedAliases() {

        File aliasFile = new File(Globals.getGenomeCacheDirectory(), id + "_alias.tab");

        if (aliasFile.exists()) {
            if (chrAliasTable == null) chrAliasTable = new HashMap();

            BufferedReader br = null;

            try {
                br = new BufferedReader(new FileReader(aliasFile));
                loadChrAliases(br);
            } catch (Exception e) {
                log.error("Error loading chr alias table", e);
                MessageUtils.showMessage("<html>Error loading chromosome alias table.  Aliases will not be avaliable<br>" +
                        e.toString());
            }

            finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (IOException e) {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                }
            }
        }
    }

    public void loadChrAliases(BufferedReader br) throws IOException {
        String nextLine = "";
        while ((nextLine = br.readLine()) != null) {
            String[] kv = nextLine.split("\t");
            if (kv.length > 1) {
                chrAliasTable.put(kv[0], kv[1]);
            }
        }
    }


    public void setChromosomeMap(LinkedHashMap<String, Chromosome> chromosomeMap, boolean chromosomesAreOrdered) {
        this.chromosomeMap = chromosomeMap;
        this.chromosomeNames = new LinkedList<String>(chromosomeMap.keySet());
        if (!chromosomesAreOrdered) {
            Collections.sort(chromosomeNames, new ChromosomeComparator());
        }

        // Update the chromosome alias table with common variations
        for (String name : chromosomeNames) {
            if (name.endsWith(".fa")) {
                // Illumina aligner output
                String alias = name.substring(0, name.length() - 3);
                chrAliasTable.put(alias, name);
            }
            if (name.startsWith("gi|")) {
                // NCBI
                String alias = getNCBIName(name);
                chrAliasTable.put(alias, name);
                Chromosome chromosome = chromosomeMap.get(name);
            } else if (name.toLowerCase().startsWith("chr")) {
                // UCSC
                chrAliasTable.put(name.substring(3), name);
            } else {
                chrAliasTable.put("chr" + name, name);
            }
        }

        // These are legacy mappings,  these are now defined in the genomes alias file
        if (id.startsWith("hg") || id.equalsIgnoreCase("1kg_ref")) {
            chrAliasTable.put("23", "chrX");
            chrAliasTable.put("24", "chrY");
            chrAliasTable.put("MT", "chrM");
        } else if (id.startsWith("mm")) {
            chrAliasTable.put("21", "chrX");
            chrAliasTable.put("22", "chrY");
            chrAliasTable.put("MT", "chrM");
        } else if (id.equals("b37")) {
            chrAliasTable.put("chrM", "MT");
            chrAliasTable.put("chrX", "23");
            chrAliasTable.put("chrY", "24");

        }


    }

    /**
     * Extract the user friendly name from an NCBI accession
     * example: gi|125745044|ref|NC_002229.3|  =>  NC_002229.3
     */
    public static String getNCBIName(String name) {

        String[] tokens = name.split("\\|");
        return tokens[tokens.length - 1];
    }


    public String getHomeChromosome() {
        if (getChromosomeNames().size() == 1 || chromosomeNames.size() > MAX_WHOLE_GENOME) {
            return getChromosomeNames().get(0);
        } else {
            return Globals.CHR_ALL;
        }
    }


    public Chromosome getChromosome(String chrName) {
        return chromosomeMap.get(chrName);
    }


    public List<String> getChromosomeNames() {
        return chromosomeNames;
    }


    public Collection<Chromosome> getChromosomes() {
        return chromosomeMap.values();
    }


    public long getLength() {
        if (length < 0) {
            length = 0;
            for (Chromosome chr : chromosomeMap.values()) {
                length += chr.getLength();
            }
        }
        return length;
    }


    public long getCumulativeOffset(String chr) {

        Long cumOffset = cumulativeOffsets.get(chr);
        if (cumOffset == null) {
            long offset = 0;
            for (String c : getChromosomeNames()) {
                if (chr.equals(c)) {
                    break;
                }
                offset += getChromosome(c).getLength();
            }
            cumOffset = new Long(offset);
            cumulativeOffsets.put(chr, cumOffset);
        }
        return cumOffset.longValue();
    }

    /**
     * Covert the chromosome coordinate in BP to genome coordinates in KBP
     *
     * @param chr
     * @param locationBP
     * @return
     */
    public int getGenomeCoordinate(String chr, int locationBP) {
        return (int) ((getCumulativeOffset(chr) + locationBP) / 1000);
    }

    /**
     * Convert the genome coordinates in KBP to a chromosome coordinate
     */
    public ChromosomeCoordinate getChromosomeCoordinate(int genomeKBP) {

        long cumOffset = 0;
        for (String c : chromosomeNames) {
            int chrLen = getChromosome(c).getLength();
            if ((cumOffset + chrLen) / 1000 > genomeKBP) {
                int bp = (int) (genomeKBP * 1000 - cumOffset);
                return new ChromosomeCoordinate(c, bp);
            }
            cumOffset += chrLen;
        }

        String c = chromosomeNames.get(chromosomeNames.size() - 1);
        int bp = (int) (genomeKBP - cumOffset) * 1000;
        return new ChromosomeCoordinate(c, bp);
    }

    /**
     * Method description
     *
     * @return
     */
    public String getId() {
        return id;
    }


    public String getAnnotationURL() {
        return annotationURL;
    }

    public String getNextChrName(String chr) {
        List<String> chrList = getChromosomeNames();
        for (int i = 0; i < chrList.size() - 1; i++) {
            if (chrList.get(i).equals(chr)) {
                return chrList.get(i + 1);
            }
        }
        return null;
    }

    public String getPrevChrName(String chr) {
        List<String> chrList = getChromosomeNames();
        for (int i = chrList.size() - 1; i > 0; i--) {
            if (chrList.get(i).equals(chr)) {
                return chrList.get(i - 1);
            }
        }
        return null;
    }

    public void setAnnotationURL(String annotationURL) {
        this.annotationURL = annotationURL;
    }


    public static class ChromosomeCoordinate {
        private String chr;
        private int coordinate;

        public ChromosomeCoordinate(String chr, int coordinate) {
            this.chr = chr;
            this.coordinate = coordinate;
        }

        public String getChr() {
            return chr;
        }

        public int getCoordinate() {
            return coordinate;
        }
    }

    /**
     * Comparator for chromosome names.
     */
    public static class ChromosomeComparator implements Comparator<String> {

        /**
         * @param chr1
         * @param chr2
         * @return
         */
        public int compare(String chr1, String chr2) {

            try {

                // Special rule -- put the mitochondria at the end
                if (chr1.equals("chrM") || chr1.equals("MT")) {
                    return 1;
                } else if (chr2.equals("chrM") || chr2.equals("MT")) {
                    return -1;
                }

                // Find the first digit
                int idx1 = findDigitIndex(chr1);
                int idx2 = findDigitIndex(chr2);
                if (idx1 == idx2) {
                    String alpha1 = idx1 == -1 ? chr1 : chr1.substring(0, idx1);
                    String alpha2 = idx2 == -1 ? chr2 : chr2.substring(0, idx2);
                    int alphaCmp = alpha1.compareTo(alpha2);
                    if (alphaCmp != 0) {
                        return alphaCmp;
                    } else {
                        int dig1 = Integer.parseInt(chr1.substring(idx1));
                        int dig2 = Integer.parseInt(chr2.substring(idx2));
                        return dig1 - dig2;
                    }
                } else if (idx1 == -1) {
                    return 1;

                } else if (idx2 == -1) {
                    return -1;
                }
                return idx1 - idx2;
            }
            catch (Exception numberFormatException) {
                return 0;
            }

        }

        int findDigitIndex(String chr) {

            int n = chr.length() - 1;
            if (!Character.isDigit(chr.charAt(n))) {
                return -1;
            }

            for (int i = n - 1; i > 0; i--) {
                if (!Character.isDigit(chr.charAt(i))) {
                    return i + 1;
                }
            }
            return 0;
        }


    }

    public synchronized GeneManager getGeneManager() {


        if (geneManager == null) {
            String genomeId = getId();
            //GenomeDescriptor genomeDescriptor = IGV.getInstance().getGenomeManager().getGenomeDescriptor(genomeId);
            AsciiLineReader reader = GeneManager.getGeneReader(descriptor);
            if (reader != null) {
                try {

                    geneManager = new GeneManager(this, descriptor.getGeneTrackName());
                    String geneFilename = descriptor.getGeneFileName();
                    FeatureParser parser = AbstractFeatureParser.getInstanceFor(new ResourceLocator(geneFilename), this);
                    if (parser == null) {
                        MessageUtils.showMessage("ERROR: Unrecognized annotation file format: " + geneFilename +
                                "<br>Annotations for genome: " + genomeId + " will not be loaded.");
                    } else {
                        List<org.broad.tribble.Feature> genes = parser.loadFeatures(reader);
                        for (org.broad.tribble.Feature gene : genes) {
                            geneManager.addGene((IGVFeature) gene);
                        }

                        geneManager.sortGeneLists();
                    }

                    geneManager.setTrackProperties(parser.getTrackProperties());

                } catch (Exception e) {
                    log.error("Error loading geneManager", e);
                }
                finally {

                    if (reader != null) {
                        reader.close();
                    }

                }
            }
        }

        return geneManager;
    }
}
