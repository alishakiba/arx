/*
 * ARX: Powerful Data Anonymization
 * Copyright 2012 - 2016 Fabian Prasser, Florian Kohlmayer and contributors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.deidentifier.arx.risk.msu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.carrotsearch.hppc.LongObjectOpenHashMap;
import com.carrotsearch.hppc.cursors.ObjectCursor;

/**
 * A set of items
 * 
 * @author Fabian Prasser
 */
public class SUDA2IndexedItemSet {

    /** The underlying map */
    private final LongObjectOpenHashMap<SUDA2Item> items = new LongObjectOpenHashMap<SUDA2Item>();
    /** The reference item*/
    private final SUDA2Item reference;
    
    /**
     * Creates a new instance
     * @param reference
     */
    public SUDA2IndexedItemSet(SUDA2Item reference) {
        this.reference = reference;
    }
    
    /**
     * Creates a new instance
     */
    public SUDA2IndexedItemSet() {
        this.reference = null;
    }

    /**
     * Returns a list containing items sorted by rank
     * @param items
     * @return
     */
    public SUDA2ItemList getItemList() {

        // Create list and sort by support, move all null values to the end
        List<SUDA2Item> list = new ArrayList<SUDA2Item>();
        Iterator<ObjectCursor<SUDA2Item>> iter = items.values().iterator();
        while (iter.hasNext()) {
            list.add(iter.next().value);
        }
        Collections.sort(list, new Comparator<SUDA2Item>() {
            @Override
            public int compare(SUDA2Item o1, SUDA2Item o2) {
                return o1.getSupport() < o2.getSupport() ? -1 :
                       o1.getSupport() > o2.getSupport() ? +1 : 0;
            }
        });
        
        // Return
        return new SUDA2ItemList(list, reference);
    }

    /**
     * Either returns an existing entry or creates a new one
     * @param column
     * @param value
     * @return
     */
    public SUDA2Item getOrCreate(int column, int value) {
        long id = SUDA2Item.getId(column, value);
        SUDA2Item item;
        if (items.containsKey(id)) {
            item = items.lget(); 
        } else {
            item = new SUDA2Item(column, value);
            items.put(id, item);
        }
        return item;
    }

    /**
     * Returns all 1-MSUs in this set
     * @return
     */
    public Set<SUDA2ItemSet> getOneMSUs() {
        Set<SUDA2ItemSet> result = new HashSet<SUDA2ItemSet>();
        Iterator<ObjectCursor<SUDA2Item>> iter = items.values().iterator();
        while (iter.hasNext()) {
            SUDA2Item item = iter.next().value;
            if (item.getSupport() == 1) {
                result.add(new SUDA2ItemSet(item));
            }
        }
        return result;
    }
}
