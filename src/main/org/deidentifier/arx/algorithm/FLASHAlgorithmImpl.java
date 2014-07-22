/*
 * ARX: Efficient, Stable and Optimal Data Anonymization
 * Copyright (C) 2012 - 2014 Florian Kohlmayer, Fabian Prasser
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.deidentifier.arx.algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import org.deidentifier.arx.framework.check.INodeChecker;
import org.deidentifier.arx.framework.check.history.History;
import org.deidentifier.arx.framework.lattice.Lattice;
import org.deidentifier.arx.framework.lattice.Node;
import org.deidentifier.arx.framework.lattice.NodeTrigger;

/**
 * This class implements the FLASH algorithm
 * 
 * @author Fabian Prasser
 * @author Florian Kohlmayer
 */
public class FLASHAlgorithmImpl extends AbstractAlgorithm {

    /** Configuration for the binary phase*/
    private final FLASHConfiguration binaryPhaseConfiguration;
    /** Configuration for the linear phase*/
    private final FLASHConfiguration linearPhaseConfiguration;

    /** Are the pointers for a node with id 'index' already sorted?. */
    protected final boolean[]           sorted;

    /** The strategy. */
    protected final FLASHStrategy       strategy;

    /** The history */
    protected History                   history;

    /**
     * Creates a new instance
     * 
     * @param lattice
     * @param checker
     * @param strategy
     * @param firstPhase
     * @param secondPhase
     */
    public FLASHAlgorithmImpl(Lattice lattice, 
                              INodeChecker checker, 
                              FLASHStrategy strategy,
                              FLASHConfiguration binaryPhaseConfiguration,
                              FLASHConfiguration linearPhaseConfiguration) {
        
        super(lattice, checker);
        this.strategy = strategy;
        this.sorted = new boolean[lattice.getSize()];
        this.history = checker.getHistory();
        this.binaryPhaseConfiguration = binaryPhaseConfiguration;
        this.linearPhaseConfiguration = linearPhaseConfiguration;
    }

    @Override
    public void traverse() {
        
        // Check bottom for speed and for estimating information loss
        if (!lattice.getBottom().hasProperty(Node.PROPERTY_CHECKED)) {
            INodeChecker.Result result = checker.check(lattice.getBottom(), true);
            lattice.getBottom().setInformationLoss(result.informationLoss);
            lattice.getBottom().setProperty(Node.PROPERTY_FORCE_SNAPSHOT);
        }
        
        // Determine configuration for the outer loop
        FLASHConfiguration outerLoopConfiguration;
        if (binaryPhaseConfiguration.active){
            outerLoopConfiguration = binaryPhaseConfiguration;
        } else {
            outerLoopConfiguration = linearPhaseConfiguration;
        }

        // Initialize
        PriorityQueue<Node> queue = new PriorityQueue<Node>(11, strategy);
        
        // For each node in the lattice
        int length = lattice.getLevels().length;
        for (int i = 0; i < length; i++) {
            for (Node node : this.getUnsetNodesAndSort(i, outerLoopConfiguration.triggerSkip)) {
                
                // Run the correct phase
                if (binaryPhaseConfiguration.active){
                    
                    checker.getHistory().setEvictionTrigger(binaryPhaseConfiguration.triggerSnapshotEvict);
                    checker.getHistory().setStorageTrigger(binaryPhaseConfiguration.triggerSnapshotStore);
                    binarySearch(node, queue);
                    
                } else {
                    
                    checker.getHistory().setEvictionTrigger(linearPhaseConfiguration.triggerSnapshotEvict);
                    checker.getHistory().setStorageTrigger(linearPhaseConfiguration.triggerSnapshotStore);
                    linearSearch(node);
                }
            }
        }
        
        // Determine information loss of top-node if it can be 
        // used for estimating minimum and maximum information
        // loss for tagged nodes
        if ((checker.getMetric().isMonotonic() ||
             checker.getConfiguration().getMaxOutliers() == 0d) &&
             lattice.getTop().getInformationLoss() == null) {
            
            // Independent evaluation or check
            if (checker.getMetric().isIndependent()) {
                lattice.getTop().setInformationLoss(checker.getMetric().evaluate(lattice.getTop(), null));
            } else {
                lattice.getTop().setChecked(checker.check(lattice.getTop(), true));
            }
        }
    }
    
    /**
     * Implements the FLASH algorithm (without outer loop)
     * @param start
     * @param queue
     */
    private void binarySearch(Node start, PriorityQueue<Node> queue) {

        // Add to queue
        queue.add(start);

        // While queue is not empty
        while (!queue.isEmpty()) {

            // Remove head and process
            Node head = queue.poll();
            if (!binaryPhaseConfiguration.triggerSkip.appliesTo(head)) {

                // First phase
                List<Node> path = findPath(head, binaryPhaseConfiguration.triggerSkip);
                head = checkPathBinary(path, binaryPhaseConfiguration.triggerSkip, queue);

                // Second phase
                if (linearPhaseConfiguration.active && head != null) {

                    // Change strategies
                    history.setEvictionTrigger(linearPhaseConfiguration.triggerSnapshotEvict);
                    history.setStorageTrigger(linearPhaseConfiguration.triggerSnapshotStore);

                    // Run linear search on head
                    linearSearch(head);

                    // Switch back to previous strategies
                    history.setEvictionTrigger(binaryPhaseConfiguration.triggerSnapshotEvict);
                    history.setStorageTrigger(binaryPhaseConfiguration.triggerSnapshotStore);
                }
            }
        }

    }

    /**
     * Implements a depth-first search with predictive tagging
     * @param start
     */
    private void linearSearch(Node start) {

        // Skip this node
        if (!linearPhaseConfiguration.triggerSkip.appliesTo(start)) {
            
            // Sort successors
            this.sortSuccessors(start);
            
            // Check and tag
            this.checkAndTag(start, linearPhaseConfiguration);
            
            // DFS
            for (final Node child : start.getSuccessors()) {
                if (!linearPhaseConfiguration.triggerSkip.appliesTo(child)) {
                    linearSearch(child);
                }
            }
        }
    }

    /**
     * Checks and tags the given transformation
     * @param node
     */
    private void checkAndTag(Node node, FLASHConfiguration configuration) {

        // Check or evaluate
        if (configuration.triggerEvaluate.appliesTo(node)) {
            node.setInformationLoss(checker.getMetric().evaluate(node, null));
        } else if (configuration.triggerCheck.appliesTo(node)) {
            node.setChecked(checker.check(node));
        }  
        
        // Store optimum
        trackOptimum(node);
        
        // Tag
        configuration.triggerTag.apply(node);
    }

    /**
     * Greedily finds a path to the top node
     * 
     * @param start The node to start the path with. Will be included
     * @param triggerSkip All nodes to which this trigger applies will be skipped
     * @return The path as a list
     */
    private List<Node> findPath(Node start, NodeTrigger triggerSkip) {
        List<Node> path = new ArrayList<Node>();
        path.add(start);
        boolean found = true;
        while (found) {
            found = false;
            this.sortSuccessors(start);
            for (final Node candidate : start.getSuccessors()) {
                if (!triggerSkip.appliesTo(candidate)) {
                    start = candidate;
                    path.add(candidate);
                    found = true;
                    break;
                }
            }
        }
        return path;
    }


    /**
     * Checks a path binary.
     * 
     * @param path
     *            The path
     * @param queue 
     */
    private Node checkPathBinary(List<Node> path, NodeTrigger triggerSkip, PriorityQueue<Node> queue) {

        // Init
        int low = 0;
        int high = path.size() - 1;
        Node lastAnonymousNode = null;

        // While not done
        while (low <= high) {

            // Init
            final int mid = (low + high) >>> 1;
            final Node node = path.get(mid);

            // Skip
            if (!triggerSkip.appliesTo(node)) {

                // Check and tag
                checkAndTag(node, binaryPhaseConfiguration);

                // Add nodes to queue
                if (!node.hasProperty(binaryPhaseConfiguration.anonymityProperty)) {
                    for (final Node up : node.getSuccessors()) {
                        if (!triggerSkip.appliesTo(up)) {
                            queue.add(up);
                        }
                    }
                }

                // Binary search
                if (node.hasProperty(binaryPhaseConfiguration.anonymityProperty)) {
                    lastAnonymousNode = node;
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }
        }
        return lastAnonymousNode;
    }

    /**
     * Returns all nodes that do not have the given property and sorts the resulting array
     * according to the strategy
     * 
     * @param level The level which is to be sorted
     * @param triggerSkip The trigger to be used for limiting the number of nodes to be sorted
     * @return A sorted array of nodes remaining on this level
     */

    protected final Node[] getUnsetNodesAndSort(int level, NodeTrigger triggerSkip) {
        
        // Create
        List<Node> result = new ArrayList<Node>();
        Node[] nlevel = lattice.getLevels()[level];
        for (Node n : nlevel) {
            if (!triggerSkip.appliesTo(n)) {
                result.add(n);
            }
        }
        
        // Sort
        Node[] resultArray = result.toArray(new Node[result.size()]);
        this.sort(resultArray);
        return resultArray;
    }

    /**
     * Sorts pointers to successor nodes according to the strategy
     * 
     * @param node The node
     */
    protected final void sortSuccessors(final Node node) {
        if (!sorted[node.id]) {
            this.sort(node.getSuccessors());
            sorted[node.id] = true;
        }
    }

    /**
     * Sorts a node array.
     * 
     * @param array The array
     */
    private final void sort(final Node[] array) {
        Arrays.sort(array, strategy);
    }
}
