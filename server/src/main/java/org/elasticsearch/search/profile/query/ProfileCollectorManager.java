/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.profile.query;

import org.apache.lucene.sandbox.search.ProfilerCollector;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A {@link CollectorManager} that takes another CollectorManager as input and wraps all Collectors generated by it
 * in an {@link InternalProfileCollector}. It delegates all the profiling to the generated collectors via {@link #getCollectorTree()}
 * and joins the different collector trees together when its {@link #reduce} method is called.
 * Note: does not support children profile collectors.
 * @param <T> the return type of the wrapped collector manager, which the reduce method returns.
 */
public final class ProfileCollectorManager<T> implements CollectorManager<InternalProfileCollector, T> {
    private final CollectorManager<? extends Collector, T> collectorManager;
    private final String reason;

    private CollectorResult collectorTree;

    public ProfileCollectorManager(CollectorManager<? extends Collector, T> collectorManager, String reason) {
        this.collectorManager = collectorManager;
        this.reason = reason;
    }

    @Override
    public InternalProfileCollector newCollector() throws IOException {
        return new InternalProfileCollector(collectorManager.newCollector(), reason);
    }

    @Override
    public T reduce(Collection<InternalProfileCollector> profileCollectors) throws IOException {
        assert profileCollectors.size() > 0 : "at least one collector expected";
        List<Collector> unwrapped = profileCollectors.stream().map(InternalProfileCollector::getWrappedCollector).toList();
        @SuppressWarnings("unchecked")
        CollectorManager<Collector, T> cm = (CollectorManager<Collector, T>) collectorManager;
        T returnValue = cm.reduce(unwrapped);

        List<CollectorResult> resultsPerProfiler = profileCollectors.stream().map(InternalProfileCollector::getCollectorTree).toList();
        long totalTime = resultsPerProfiler.stream().map(CollectorResult::getTime).reduce(0L, Long::sum);
        String collectorName = resultsPerProfiler.get(0).getName();
        assert profileCollectors.stream().map(ProfilerCollector::getReason).allMatch(reason::equals);
        assert profileCollectors.stream().map(ProfilerCollector::getName).allMatch(collectorName::equals);

        this.collectorTree = new CollectorResult(collectorName, reason, totalTime, Collections.emptyList());
        return returnValue;
    }

    public CollectorResult getCollectorTree() {
        if (this.collectorTree == null) {
            throw new IllegalStateException("A collectorTree hasn't been set yet. Call reduce() before attempting to retrieve it");
        }
        return this.collectorTree;
    }
}
