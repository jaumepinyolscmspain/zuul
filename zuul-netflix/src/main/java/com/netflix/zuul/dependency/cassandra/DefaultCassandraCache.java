/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul.dependency.cassandra;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.zuul.constants.ZuulConstants;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in memory cache implementation, backed by a Map.
 */
public class DefaultCassandraCache<K> implements CassandraCache<K> {

    /**
     * Bounds cache - 1000 feels like a safe number to start with.
     */

    private final static DynamicIntProperty maxCacheSize =
            DynamicPropertyFactory.getInstance().getIntProperty(ZuulConstants.ZUUL_CASSANDRA_CACHE_MAX_SIZE, 1000);

    private final ConcurrentMap<String, K> cacheMap;

    private final AtomicLong cacheHits = new AtomicLong(0);

    private final AtomicLong cacheMisses = new AtomicLong(0);

    private float cacheHitRatio = 0;

    private final String monitorName = this.getClass().getSimpleName();

    public DefaultCassandraCache() {
        this(new ConcurrentHashMap<>());
    }

    public DefaultCassandraCache(ConcurrentMap<String, K> cacheMap) {
        this.cacheMap = cacheMap;
    }

    /**
     * Returns cached query by keys
     *
     * @param keys
     * @return
     */
    public K fetchQuery(String... keys) {
        final String key = buildKey(keys);
        final K result = cacheMap.get(key);

        if (result != null)
            this.cacheHits.incrementAndGet();
        else
            this.cacheMisses.incrementAndGet();

        final long currentHits = this.cacheHits.get();
        this.cacheHitRatio = (float) currentHits / (currentHits + this.cacheMisses.get());

        return result;
    }

    /**
     * stores a given response by keys
     *
     * @param response
     * @param keys
     */
    public void storeQuery(K response, String... keys) {
        final String key = buildKey(keys);

        if (!this.cacheMap.containsKey(key)) {
            // checks if inserting this item will increase cache over desired size
            final int removeCount = (this.cacheMap.size() + 1) - this.getMaxCacheSize();

            if (removeCount > 0) {
                // I am simply going to remove as many items as necessary in the order that the iterator returns them.
                // in practice it will usually only need to remove one item, but if we updated the fast property
                // to half the cache size there would be a lot more
                final Iterator<String> it = this.cacheMap.keySet().iterator();
                for (int i = 0; i < removeCount; i++) {
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }

        this.cacheMap.put(key, response);
    }

    public long getCacheHits() {
        return this.cacheHits.get();
    }

    public long getCacheMisses() {
        return this.cacheMisses.get();
    }

    public double getCacheHitRatio() {
        return this.cacheHitRatio;
    }

    public long getCacheEntries() {
        return this.cacheMap.size();
    }

    /**
     * We just concatenate the Strings.
     *
     * @param keys
     * @return
     */
    public static final String buildKey(String... keys) {
        return Arrays.toString(keys);
    }

    protected int getMaxCacheSize() {
        return maxCacheSize.get();
    }
}