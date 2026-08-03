package com.Optum.CyFHIR.procedures;

import apoc.convert.ConvertConfig;
import apoc.result.MapResult;
import apoc.util.Util;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps the existing APOC-compatible tree conversion API. The implementation is
 * deliberately independent of the removed :entry model.
 */
public class Convert {

    @Procedure(name = "cyfhir.convert.toTree")
    @Description("Creates nested maps from supplied paths without assuming an :entry root.")
    public Stream<MapResult> toTree(@Name("paths") List<Path> paths,
            @Name(value = "lowerCaseRels", defaultValue = "true") boolean lowerCaseRels,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {
        if (paths == null || paths.isEmpty()) {
            return Stream.of(new MapResult(Collections.<String, Object>emptyMap()));
        }

        ConvertConfig convertConfig = new ConvertConfig(config == null
                ? Collections.<String, Object>emptyMap() : config);
        Map<String, List<String>> nodeFilters = convertConfig.getNodes();
        Map<String, List<String>> relationshipFilters = convertConfig.getRels();
        Map<Long, Map<String, Object>> nodeMaps = new LinkedHashMap<Long, Map<String, Object>>();

        for (Path path : paths) {
            Iterator<Entity> iterator = path.iterator();
            while (iterator.hasNext()) {
                Entity entity = iterator.next();
                if (!(entity instanceof Node)) {
                    continue;
                }
                Node current = (Node) entity;
                Map<String, Object> currentMap = nodeMaps.computeIfAbsent(current.getId(),
                        id -> toMap(current, nodeFilters));

                if (!iterator.hasNext()) {
                    continue;
                }
                Entity relationshipEntity = iterator.next();
                if (!(relationshipEntity instanceof Relationship) || !iterator.hasNext()) {
                    continue;
                }
                Relationship relationship = (Relationship) relationshipEntity;
                Entity nextEntity = iterator.next();
                if (!(nextEntity instanceof Node)) {
                    continue;
                }
                Node child = (Node) nextEntity;
                String relationshipName = lowerCaseRels
                        ? relationship.getType().name().toLowerCase(Locale.ROOT)
                        : relationship.getType().name();

                List<Map<String, Object>> children = childList(currentMap, relationshipName);
                if (!containsNode(children, child.getId())) {
                    Map<String, Object> childMap = toMap(child, nodeFilters);
                    addRelationshipProperties(childMap, relationshipName, relationship, relationshipFilters);
                    nodeMaps.put(child.getId(), childMap);
                    children.add(childMap);
                }
            }
        }

        return paths.stream()
                .map(Path::startNode)
                .distinct()
                .map(node -> nodeMaps.get(node.getId()))
                .map(map -> map == null ? Collections.<String, Object>emptyMap() : map)
                .map(MapResult::new);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childList(Map<String, Object> parent, String relationshipName) {
        Object existing = parent.get(relationshipName);
        if (existing instanceof List) {
            return (List<Map<String, Object>>) existing;
        }
        List<Map<String, Object>> children = new ArrayList<Map<String, Object>>();
        parent.put(relationshipName, children);
        return children;
    }

    private static boolean containsNode(List<Map<String, Object>> children, long nodeId) {
        for (Map<String, Object> child : children) {
            Object id = child.get("_id");
            if (id instanceof Number && ((Number) id).longValue() == nodeId) {
                return true;
            }
        }
        return false;
    }

    private static void addRelationshipProperties(Map<String, Object> child, String relationshipName,
            Relationship relationship, Map<String, List<String>> filters) {
        Map<String, Object> properties = relationship.getAllProperties();
        if (properties.isEmpty()) {
            return;
        }
        if (filters != null && filters.containsKey(relationshipName)) {
            properties = filterProperties(properties, filters.get(relationshipName));
        }
        String prefix = relationshipName + ".";
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            child.put(prefix + property.getKey(), property.getValue());
        }
    }

    private static Map<String, Object> toMap(Node node, Map<String, List<String>> filters) {
        Map<String, Object> properties = node.getAllProperties();
        Map<String, Object> result = new LinkedHashMap<String, Object>(properties.size() + 2);
        String type = Util.labelString(node);
        result.put("_id", node.getId());
        result.put("_type", type);
        if (filters != null && filters.containsKey(type)) {
            properties = filterProperties(properties, filters.get(type));
        }
        result.putAll(properties);
        return result;
    }

    private static Map<String, Object> filterProperties(Map<String, Object> properties, List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return properties;
        }
        boolean exclude = filters.get(0).startsWith("-");
        return properties.entrySet().stream()
                .filter(entry -> exclude
                        ? !filters.contains("-" + entry.getKey())
                        : filters.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> right, LinkedHashMap::new));
    }
}
