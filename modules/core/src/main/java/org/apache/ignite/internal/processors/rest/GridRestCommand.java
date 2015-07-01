/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.rest;

import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Supported commands.
 */
public enum GridRestCommand {
    /*
     * API commands.
     * =============
     */

    /** Get cached value. */
    CACHE_GET("get"),

    /** Contains cached value. */
    CACHE_CONTAINS_KEY("containskey"),

    /** Contains cached values. */
    CACHE_CONTAINS_KEYS("containskeys"),

    /** Get several cached values. */
    CACHE_GET_ALL("getall"),

    /** Store value in cache and return previous value. */
    CACHE_GET_AND_PUT("getandput"),

    /** Store value in cache and return previous value. */
    CACHE_GET_AND_PUT_IF_ABSENT("getandputifabsent"),

    /** Store value in cache. */
    CACHE_PUT("put"),

    /** Store value in cache. */
    CACHE_PUT_IF_ABSENT("putifabsent"),

    /** Store value in cache if it doesn't exist. */
    CACHE_ADD("add"),

    /** Store several values in cache. */
    CACHE_PUT_ALL("putall"),

    /** Remove value from cache. */
    CACHE_REMOVE("rmv"),

    /** Remove value from cache. */
    CACHE_REMOVE_VALUE("rmvvalue"),

    /** Remove value from cache. */
    CACHE_GET_AND_REMOVE("getandrmv"),

    /** Remove several values from cache. */
    CACHE_REMOVE_ALL("rmvall"),

    /** Replace cache value only if there is currently a mapping for it. */
    CACHE_REPLACE("rep"),

    /** Replace cache value only if there is currently a mapping for it. */
    CACHE_GET_AND_REPLACE("getandreplace"),

    /** Compare and set. */
    CACHE_CAS("cas"),

    /** Append. */
    CACHE_APPEND("append"),

    /** Prepend. */
    CACHE_PREPEND("prepend"),

    /** Cache metrics. */
    CACHE_METRICS("cache"),

    /** Increment. */
    ATOMIC_INCREMENT("incr"),

    /** Decrement. */
    ATOMIC_DECREMENT("decr"),

    /** Grid topology. */
    TOPOLOGY("top"),

    /** Single node info. */
    NODE("node"),

    /** Task execution .*/
    EXE("exe"),

    /** Task execution .*/
    RESULT("res"),

    /** Version. */
    VERSION("version"),

    /** Name. */
    NAME("name"),

    /** Log. */
    LOG("log"),

    /** No-op. */
    NOOP("noop"),

    /** Quit. */
    QUIT("quit"),

    /** Run script. */
    RUN_SCRIPT("runscript"),

    /** Execute map reduce script. */
    EXECUTE_MAP_REDUCE_SCRIPT("excmapreduce"),

    /** Execute sql query. */
    EXECUTE_SQL_QUERY("qryexecute"),

    /** Execute sql fields query. */
    EXECUTE_SQL_FIELDS_QUERY("qryfieldsexecute"),

    /** Fetch query results. */
    FETCH_SQL_QUERY("qryfetch");

    /** Enum values. */
    private static final GridRestCommand[] VALS = values();

    /** Key to enum map. */
    private static final Map<String, GridRestCommand> cmds = new HashMap<>();

    /**
     * Map keys to commands.
     */
    static {
        for (GridRestCommand cmd : values())
            cmds.put(cmd.key(), cmd);
    }

    /** Command key. */
    private final String key;

    /**
     * @param key Key.
     */
    GridRestCommand(String key) {
        this.key = key;
    }

    /**
     * @param ord Byte to convert to enum.
     * @return Enum.
     */
    @Nullable public static GridRestCommand fromOrdinal(int ord) {
        return ord >= 0 && ord < VALS.length ? VALS[ord] : null;
    }

    /**
     * @param key Key.
     * @return Command.
     */
    @Nullable public static GridRestCommand fromKey(String key) {
        return cmds.get(key);
    }

    /**
     * @return Command key.
     */
    public String key() {
        return key;
    }
}
