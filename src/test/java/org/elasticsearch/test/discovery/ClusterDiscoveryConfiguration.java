/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test.discovery;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.google.common.primitives.Ints;
import org.elasticsearch.Version;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.SettingsSource;
import org.elasticsearch.transport.local.LocalTransport;

import java.util.HashSet;
import java.util.Set;

public class ClusterDiscoveryConfiguration extends SettingsSource {

    static {
        //see https://github.com/elasticsearch/elasticsearch/pull/8634
        assert Version.CURRENT.onOrBefore(Version.V_1_4_0) : "Remove this class as the required fixes should have been released with core";
    }

    public static Settings DEFAULT_SETTINGS = ImmutableSettings.settingsBuilder()
            .put("gateway.type", "local")
            .put("discovery.type", "zen")
            .build();

    final int numOfNodes;

    final Settings baseSettings;

    public ClusterDiscoveryConfiguration(int numOfNodes) {
        this(numOfNodes, ImmutableSettings.EMPTY);
    }

    public ClusterDiscoveryConfiguration(int numOfNodes, Settings extraSettings) {
        this.numOfNodes = numOfNodes;
        this.baseSettings = ImmutableSettings.builder().put(DEFAULT_SETTINGS).put(extraSettings).build();
    }

    @Override
    public Settings node(int nodeOrdinal) {
        return baseSettings;
    }

    @Override
    public Settings transportClient() {
        return baseSettings;
    }

    public static class UnicastZen extends ClusterDiscoveryConfiguration {

        private final int[] unicastHostOrdinals;
        private final int basePort;

        public UnicastZen(int numOfNodes, ElasticsearchIntegrationTest.Scope scope) {
            this(numOfNodes, numOfNodes, scope);
        }

        public UnicastZen(int numOfNodes, Settings extraSettings, ElasticsearchIntegrationTest.Scope scope) {
            this(numOfNodes, numOfNodes, extraSettings, scope);
        }

        public UnicastZen(int numOfNodes, int numOfUnicastHosts, ElasticsearchIntegrationTest.Scope scope) {
            this(numOfNodes, numOfUnicastHosts, ImmutableSettings.EMPTY, scope);
        }

        public UnicastZen(int numOfNodes, int numOfUnicastHosts, Settings extraSettings, ElasticsearchIntegrationTest.Scope scope) {
            super(numOfNodes, extraSettings);
            if (numOfUnicastHosts == numOfNodes) {
                unicastHostOrdinals = new int[numOfNodes];
                for (int i = 0; i < numOfNodes; i++) {
                    unicastHostOrdinals[i] = i;
                }
            } else {
                Set<Integer> ordinals = new HashSet<>(numOfUnicastHosts);
                while (ordinals.size() != numOfUnicastHosts) {
                    ordinals.add(RandomizedTest.randomInt(numOfNodes - 1));
                }
                unicastHostOrdinals = Ints.toArray(ordinals);
            }
            this.basePort = calcBasePort(scope);
        }

        public UnicastZen(int numOfNodes, int[] unicastHostOrdinals, ElasticsearchIntegrationTest.Scope scope) {
            this(numOfNodes, ImmutableSettings.EMPTY, unicastHostOrdinals, scope);
        }

        public UnicastZen(int numOfNodes, Settings extraSettings, int[] unicastHostOrdinals, ElasticsearchIntegrationTest.Scope scope) {
            super(numOfNodes, extraSettings);
            this.unicastHostOrdinals = unicastHostOrdinals;
            this.basePort = calcBasePort(scope);
        }

        private static int calcBasePort(ElasticsearchIntegrationTest.Scope scope) {
            // note that this has properly co-exist with the port logic at InternalTestCluster's constructor
            return 30000 +
                    1000 * (ElasticsearchIntegrationTest.CHILD_JVM_ID % 60) + // up to 30 jvms
                    //up to 100 nodes per cluster
                    100 * scopeId(scope);
        }

        private static int scopeId(ElasticsearchIntegrationTest.Scope scope) {
            switch(scope) {
                case GLOBAL:
                    //we reserve a special base port for global clusters, as they stick around
                    //the assumption is that no counter is needed as there's only one global cluster per jvm
                    return 0;
                default:
                    //ports can be reused as suite or test clusters are never run concurrently
                    //prevent conflicts between jvms by never going above 9
                    return RandomizedTest.randomIntBetween(1, 9);
            }
        }

        @Override
        public Settings node(int nodeOrdinal) {
            ImmutableSettings.Builder builder = ImmutableSettings.builder()
                    .put("discovery.zen.ping.multicast.enabled", false);

            String[] unicastHosts = new String[unicastHostOrdinals.length];
            String mode = baseSettings.get("node.mode", InternalTestCluster.NODE_MODE);
            if (mode.equals("local")) {
                builder.put(LocalTransport.TRANSPORT_LOCAL_ADDRESS, "node_" + nodeOrdinal);
                for (int i = 0; i < unicastHosts.length; i++) {
                    unicastHosts[i] = "node_" + unicastHostOrdinals[i];
                }
            } else {
                // we need to pin the node port & host so we'd know where to point things
                builder.put("transport.tcp.port", basePort + nodeOrdinal);
                builder.put("transport.host", "localhost");
                for (int i = 0; i < unicastHosts.length; i++) {
                    unicastHosts[i] = "localhost:" + (basePort + unicastHostOrdinals[i]);
                }
            }
            builder.putArray("discovery.zen.ping.unicast.hosts", unicastHosts);
            return builder.put(super.node(nodeOrdinal)).build();
        }
    }
}
