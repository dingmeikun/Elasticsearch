/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elasticsearch.action.admin.indices.shard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.admin.indices.stats.IndexShardStats;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.common.xcontent.XContentFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class IndexShardStatsResponse extends BroadcastResponse implements ToXContent {

    private ShardStats[] shards;

    private ImmutableMap<ShardRouting, ShardStats> shardStatsMap;

    IndexShardStatsResponse() {

    }

    IndexShardStatsResponse(ShardStats[] shards, int totalShards, int successfulShards, int failedShards, List<ShardOperationFailedException> shardFailures) {
        super(totalShards, successfulShards, failedShards, shardFailures);
        this.shards = shards;
    }

    public ImmutableMap<ShardRouting, ShardStats> asMap() {
        if (shardStatsMap == null) {
            ImmutableMap.Builder<ShardRouting, ShardStats> mb = ImmutableMap.builder();
            for (ShardStats ss : shards) {
                mb.put(ss.getShardRouting(), ss);
            }

            shardStatsMap = mb.build();
        }
        return shardStatsMap;
    }

    public ShardStats[] getShards() {
        return this.shards;
    }

    public ShardStats getAt(int position) {
        return shards[position];
    }

    public IndexStats getIndex(String index) {
        return getIndices().get(index);
    }

    private Map<String, IndexStats> indicesStats;

    public Map<String, IndexStats> getIndices() {
        if (indicesStats != null) {
            return indicesStats;
        }
        Map<String, IndexStats> indicesStats = Maps.newHashMap();

        Set<String> indices = Sets.newHashSet();
        for (ShardStats shard : shards) {
            indices.add(shard.getShardRouting().getIndex());
        }

        for (String index : indices) {
            List<ShardStats> shards = new ArrayList<>();
            for (ShardStats shard : this.shards) {
                if (shard.getShardRouting().index().equals(index)) {
                    shards.add(shard);
                }
            }
            indicesStats.put(index, new IndexStats(index, shards.toArray(new ShardStats[shards.size()])));
        }
        this.indicesStats = indicesStats;
        return indicesStats;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        shards = new ShardStats[in.readVInt()];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = ShardStats.readShardStats(in);
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(shards.length);
        for (ShardStats shard : shards) {
            shard.writeTo(out);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        String level = params.param("level", "indices");
        boolean isLevelValid = "indices".equalsIgnoreCase(level) || "shards".equalsIgnoreCase(level) || "cluster".equalsIgnoreCase(level);
        if (!isLevelValid) {
            return builder;
        }

        if ("indices".equalsIgnoreCase(level) || "shards".equalsIgnoreCase(level)) {
            builder.startObject(Fields.INDICES);
            for (IndexStats indexStats : getIndices().values()) {
                builder.startObject(indexStats.getIndex(), XContentBuilder.FieldCaseConversion.NONE);

                builder.startObject("primaries");
                indexStats.getPrimaries().toXContent(builder, params);
                builder.endObject();

                builder.startObject("total");
                indexStats.getTotal().toXContent(builder, params);
                builder.endObject();

                if ("shards".equalsIgnoreCase(level)) {
                    builder.startObject(Fields.SHARDS);
                    for (IndexShardStats indexShardStats : indexStats) {
                        builder.startArray(Integer.toString(indexShardStats.getShardId().id()));
                        for (ShardStats shardStats : indexShardStats) {
                            builder.startObject();
                            shardStats.toXContent(builder, params);
                            builder.endObject();
                        }
                        builder.endArray();
                    }
                    builder.endObject();
                }
                builder.endObject();
            }
            builder.endObject();
        }

        return builder;
    }

    static final class Fields {
        static final XContentBuilderString INDICES = new XContentBuilderString("indices");
        static final XContentBuilderString SHARDS = new XContentBuilderString("shards");
    }

    @Override
    public String toString() {
        try {
            XContentBuilder builder = XContentFactory.jsonBuilder().prettyPrint();
            builder.startObject();
            toXContent(builder, EMPTY_PARAMS);
            builder.endObject();
            return builder.string();
        } catch (IOException e) {
            return "{ \"error\" : \"" + e.getMessage() + "\"}";
        }
    }
}
