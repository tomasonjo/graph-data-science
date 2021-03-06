/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.louvain;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.graphalgo.AlgoBaseProc;
import org.neo4j.graphalgo.GdsCypher;
import org.neo4j.graphalgo.TestSupport.AllGraphNamesTest;
import org.neo4j.graphalgo.WriteConfigTest;
import org.neo4j.graphalgo.compat.MapUtil;
import org.neo4j.graphalgo.core.CypherMapWrapper;
import org.neo4j.graphdb.QueryExecutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.graphalgo.CommunityHelper.assertCommunities;
import static org.neo4j.graphalgo.ThrowableRootCauseMatcher.rootCause;
import static org.neo4j.graphalgo.core.ProcedureConstants.GRAPH_IMPL_KEY;
import static org.neo4j.graphalgo.core.ProcedureConstants.SEED_PROPERTY_KEY;
import static org.neo4j.graphalgo.core.ProcedureConstants.TOLERANCE_KEY;

class LouvainWriteProcTest extends LouvainBaseProcTest<LouvainWriteConfig> implements
    WriteConfigTest<LouvainWriteConfig, Louvain> {

    @Override
    public Class<? extends AlgoBaseProc<?, Louvain, LouvainWriteConfig>> getProcedureClazz() {
        return LouvainWriteProc.class;
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("org.neo4j.graphalgo.louvain.LouvainBaseProcTest#graphVariations")
    void testWrite(GdsCypher.QueryBuilder queryBuilder, String testCaseName) {
        String writeProperty = "myFancyCommunity";
        @Language("Cypher") String query = queryBuilder
            .algo("louvain")
            .writeMode()
            .addParameter("writeProperty", writeProperty)
            .yields(
                "communityCount",
                "modularity",
                "modularities",
                "ranLevels",
                "createMillis",
                "computeMillis",
                "writeMillis",
                "postProcessingMillis",
                "communityDistribution",
                "configuration"
            );

        runQueryWithRowConsumer(query, row -> {
            long communityCount = row.getNumber("communityCount").longValue();
            double modularity = row.getNumber("modularity").doubleValue();
            List<Double> modularities = (List<Double>) row.get("modularities");
            long levels = row.getNumber("ranLevels").longValue();
            long createMillis = row.getNumber("createMillis").longValue();
            long computeMillis = row.getNumber("computeMillis").longValue();
            long writeMillis = row.getNumber("writeMillis").longValue();

            assertEquals(3, communityCount, "wrong community count");
            assertEquals(2, modularities.size(), "invalud modularities");
            assertEquals(2, levels, "invalid level count");
            assertUserInput(row, "includeIntermediateCommunities", false);
            assertTrue(modularity > 0, "wrong modularity value");
            assertTrue(createMillis >= 0, "invalid loadTime");
            assertTrue(writeMillis >= 0, "invalid writeTime");
            assertTrue(computeMillis >= 0, "invalid computeTime");
        });
        assertWriteResult(RESULT, writeProperty);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("org.neo4j.graphalgo.louvain.LouvainBaseProcTest#graphVariations")
    void testWriteIntermediateCommunities(GdsCypher.QueryBuilder queryBuilder, String testCaseName) {
        String writeProperty = "myFancyCommunity";
        String query = queryBuilder
            .algo("louvain")
            .writeMode()
            .addParameter("writeProperty", writeProperty)
            .addParameter("includeIntermediateCommunities", true)
            .yields("configuration");

        runQueryWithRowConsumer(query, row -> {
            assertUserInput(row, "includeIntermediateCommunities", true);
        });

        runQueryWithRowConsumer(String.format("MATCH (n) RETURN n.%s as %s", writeProperty, writeProperty), row -> {
            Object maybeList = row.get(writeProperty);
            assertTrue(maybeList instanceof long[]);
            long[] communities = (long[]) maybeList;
            assertEquals(2, communities.length);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "writeProperty: null,",
        "writeProperty: '',",
    })
    void testWriteRequiresWritePropertyToBeSet(String writePropertyParameter) {
        String query = "CALL gds.louvain.write({" +
                       writePropertyParameter +
                       "    nodeProjection: ['Node']," +
                       "    relationshipProjection: {" +
                       "      TYPE: {" +
                       "        type: 'TYPE'," +
                       "        projection: 'UNDIRECTED'" +
                       "      }" +
                       "    }" +
                       "})";

        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> runQuery(query)
        );

        assertThat(exception, rootCause(
            IllegalArgumentException.class,
            "No value specified for the mandatory configuration parameter `writeProperty`"
        ));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("org.neo4j.graphalgo.louvain.LouvainBaseProcTest#graphVariations")
    void testWriteWithSeeding(GdsCypher.QueryBuilder queryBuilder, String testCaseName) {
        String writeProperty = "myFancyWriteProperty";
        String query = queryBuilder
            .algo("louvain")
            .writeMode()
            .addParameter("writeProperty", writeProperty)
            .addParameter("seedProperty", "seed")
            .yields("communityCount", "ranLevels");

        runQueryWithRowConsumer(
            query,
            row -> {
                assertEquals(3, row.getNumber("communityCount").longValue(), "wrong community count");
                assertEquals(1, row.getNumber("ranLevels").longValue(), "wrong number of levels");
            }
        );
        assertWriteResult(RESULT, writeProperty);
    }

    // TODO: add a variation of the below with non-defaults
    @Test
    void testCreateConfigWithDefaults() {
        LouvainBaseConfig louvainConfig = LouvainWriteConfig.of(
            "",
            Optional.empty(),
            Optional.empty(),
            createMinimalConfig(CypherMapWrapper.empty())
        );
        assertFalse(louvainConfig.includeIntermediateCommunities());
        assertEquals(10, louvainConfig.maxLevels());
        assertEquals(10, louvainConfig.maxIterations());
        assertEquals(0.0001D, louvainConfig.tolerance());
        assertNull(louvainConfig.seedProperty());
    }

    @Override
    public LouvainWriteConfig createConfig(CypherMapWrapper mapWrapper) {
        return LouvainWriteConfig.of(
            "",
            Optional.empty(),
            Optional.empty(),
            mapWrapper
        );
    }

    @Override
    public CypherMapWrapper createMinimalConfig(CypherMapWrapper mapWrapper) {
        if (!mapWrapper.containsKey("writeProperty")) {
            return mapWrapper.withString("writeProperty", "writeProperty");
        }
        return mapWrapper;
    }

    @AllGraphNamesTest
    void testOverwritingDefaults(String graphImpl) {
        Map<String, Object> config = MapUtil.map(
            GRAPH_IMPL_KEY, graphImpl,
            "includeIntermediateCommunities", true,
            "maxLevels", 42,
            "maxIterations", 42,
            TOLERANCE_KEY, 0.42,
            SEED_PROPERTY_KEY, "foobar"
        );
    }

    private void assertWriteResult(List<List<Long>> expectedCommunities, String writeProperty) {
        List<Long> actualCommunities = new ArrayList<>();
        runQueryWithRowConsumer(String.format("MATCH (n) RETURN id(n) as id, n.%s as community", writeProperty), (row) -> {
            long community = row.getNumber("community").longValue();
            int id = row.getNumber("id").intValue();
            actualCommunities.add(id, community);
        });

        assertCommunities(actualCommunities, expectedCommunities);
    }
}
