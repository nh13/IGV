package org.broad.igv.gwas;

import org.apache.log4j.Logger;
//import org.broad.igv.data.FloatArrayList;
//import org.broad.igv.data.IntArrayList;
//import org.broad.igv.data.StringArrayList;
import org.broad.igv.util.collections.FloatArrayList;
import org.broad.igv.util.collections.IntArrayList;
import org.broad.igv.util.collections.StringArrayList;



import java.util.LinkedHashMap;

/**
 * Created by IntelliJ IDEA.
 * User: jussi
 * Date: Nov 23, 2009
 * Time: 5:09:01 PM
 * To change this template use File | Settings | File Templates.
 */
public class GWASData {

    private static Logger log = Logger.getLogger(GWASData.class);


    private LinkedHashMap<String, IntArrayList> locations = new LinkedHashMap();
    private LinkedHashMap<String, FloatArrayList> values = new LinkedHashMap();
    private IntArrayList fileIndex = new IntArrayList(100);


    /**
     * Get bytecount for start of bin containing given index
     *
     * @param index
     * @return
     */

    public int getByteStartByIndex(int index) {


        int bin = (int) index / 10000;
        //log.info("index: " + index + " bin: " + bin + " bytestart: " + fileIndex.get(bin));

        return fileIndex.get(bin);

    }

    public IntArrayList getFileIndex() {
        return fileIndex;
    }

    public void setFileIndex(IntArrayList fileIndex) {
        this.fileIndex = fileIndex;
    }


    public LinkedHashMap<String, StringArrayList> getDescriptions() {
        return descriptions;
    }

    public void setDescriptions(LinkedHashMap<String, StringArrayList> descriptions) {
        this.descriptions = descriptions;
    }

    private LinkedHashMap<String, StringArrayList> descriptions = null;

    private float maxValue = 0;


    public float getMaxValue() {
        return maxValue;
    }


    /**
     * Count cumulative index for chromosomes before given chromosome.
     *
     * @param chr
     * @return
     */

    public int getCumulativeChrLocation(String chr) {

        Object[] keys = this.locations.keySet().toArray();

        int chrCounter = 0;
        int lineCounter = 0;
        while (chrCounter < keys.length && !keys[chrCounter].toString().equals(chr)) {

            //lineCounter += locations.get(chr).size();
            lineCounter += locations.get(keys[chrCounter].toString()).size();
            //log.info("chr: " + keys[chrCounter].toString() + " chrCounter:" + chrCounter + " line: " + lineCounter);

            chrCounter++;

        }

        return lineCounter;
    }

    /**
     * Count number of all locations.
     *
     * @return
     */

   /*
    
    public int countTotalLocations() {

        int locationCounter = 0;

        if (this.locations != null) {

            Object[] keys = this.locations.keySet().toArray();

            for (int i = 0; i < keys.length; i++) {
                locationCounter += locations.get(keys[i]).size();
            }
        }

        return locationCounter;

    }

  */
    /**
     * Get index of data point based on chromosomal location
     *
     * @param chr
     * @param location
     * @return index of data point in given location, -1 if not found
     */


    public int getIndexByLocation(String chr, int location) {

        if (this.locations.containsKey(chr)) {
            int[] locList = this.locations.get(chr).toArray();
            int indexCounter = 0;
            for (int loc : locList) {
                if (loc == location)
                    return indexCounter;
                indexCounter++;

            }
        }
        return -1;
    }

    /**
     * Get index of nearest data point based on chromosomal location
     *
     * @param chr
     * @param location
     * @return index of data point in nearest location, -1 if not found
     */

    public int getNearestIndexByLocation(String chr, int location) {

        int index = -1;
        if (this.locations.containsKey(chr)) {
            int[] locList = this.locations.get(chr).toArray();
            int indexCounter = 0;

            while ((locList[indexCounter] < location) && (indexCounter < locList.length)) {
                indexCounter++;
            }
            // Used to ensure that array boundaries are not exceeded
            int beforeIndex = indexCounter - 1;
            if (beforeIndex < 0)
                beforeIndex = 0;
            // Index of nearest data point before the location
            int before = locList[beforeIndex];
            // Index of nearest data point after the location
            int after = locList[indexCounter];

            // Compare which one is closer and use ase index
            if (Math.abs(location - before) < Math.abs(location - after))
                index = beforeIndex;
            else
                index = indexCounter;


        }
        return index;
    }

    /**
     * Get index of nearest data point based on given parameters
     *
     * @param chr         Chromosome
     * @param location    Chromosomal location as nucleotides
     * @param minY        Lower range of y value
     * @param maxY        Upper range of y value
     * @param maxDistance Maximum chromosomal distance as nucleotides from the given location
     * @return
     */
    public int getNearestIndexByLocation(String chr, int location, double minY, double maxY, int maxDistance) {

        int index = -1;
        int iBefore = -1;
        int iAfter = -1;

        if (this.locations.containsKey(chr)) {
            int[] locList = this.locations.get(chr).toArray();
            float[] valueList = this.values.get(chr).toArray();
            int indexCounter = 0;


            while ((indexCounter < locList.length) && locList[indexCounter] < location) {
                if (valueList[indexCounter] > minY && valueList[indexCounter] < maxY)
                    iBefore = indexCounter;
                indexCounter++;
            }


            while (indexCounter < valueList.length) {
                if (valueList[indexCounter] > minY && valueList[indexCounter] < maxY) {
                    iAfter = indexCounter;
                    break;

                }
                indexCounter++;

            }

            if (iBefore >= 0 && iAfter >= 0) {


                // Location of nearest data point before the location
                int before = locList[iBefore];
                // Location of nearest data point after the location
                int after = locList[iAfter];
                // Compare which one is closer and use ase index
                if (Math.abs(location - before) < Math.abs(location - after))
                    index = iBefore;


                else
                    index = iAfter;
            } else if (iBefore >= 0)
                index = iBefore;
            else if (iAfter >= 0)
                index = iAfter;

            if (index >= 0) {
                int distance = Math.abs(location - locList[index]);
                if (distance > maxDistance)
                    index = -1;


            }

        }


        //log.info("index: " + index + " iBefore: " + iBefore + " iAfter: " + iAfter);
        return index;
    }


    public void addLocation(String chr, int location) {
        //System.out.println("Adding: " + chr + " location: " +location);
        IntArrayList locList = new IntArrayList(1);
        if (this.locations != null && this.locations.get(chr) != null) {
            locList = this.locations.get(chr);

        }
        locList.add(location);
        this.addLocations(chr, locList);

    }

    void addLocations(String chr, IntArrayList locations) {
        if (this.locations == null) {
            this.locations = new LinkedHashMap<String, IntArrayList>();
        }
        this.locations.put(chr, locations);
    }

    public void addValue(String chr, float value) {
        FloatArrayList valueList = new FloatArrayList(1);
        if (this.values != null && this.values.get(chr) != null) {

            valueList = this.values.get(chr);

        }

        valueList.add(value);
        this.addValues(chr, valueList);
        if (this.maxValue < value)
            this.maxValue = value;

    }

    void addValues(String chr, FloatArrayList values) {
        if (this.values == null) {
            this.values = new LinkedHashMap<String, FloatArrayList>();
        }

        this.values.put(chr, values);
    }


    public void addDescription(String chr, String description) {
        StringArrayList descriptionList = new StringArrayList(1);
        if (this.descriptions != null && this.descriptions.get(chr) != null) {

            descriptionList = this.descriptions.get(chr);

        }

        descriptionList.add(description);
        this.addDescriptions(chr, descriptionList);


    }

    void addDescriptions(String chr, StringArrayList descriptions) {
        if (this.descriptions == null) {
            this.descriptions = new LinkedHashMap<String, StringArrayList>();
        }

        this.descriptions.put(chr, descriptions);
    }


    public LinkedHashMap<String, IntArrayList> getLocations() {
        return locations;
    }

    public void setLocations(LinkedHashMap<String, IntArrayList> locations) {
        this.locations = locations;
    }

    public LinkedHashMap<String, FloatArrayList> getValues() {
        return values;
    }

    public void setValues(LinkedHashMap<String, FloatArrayList> values) {
        this.values = values;
    }
}