package com.Optum.CyFHIR.procedures;

import apoc.path.LabelSequenceEvaluator;
import apoc.path.NodeEvaluators;
import apoc.path.RelationshipSequenceExpander;
import apoc.result.MapResult;
import apoc.result.PathResult;
import com.Optum.CyFHIR.models.Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.procedure.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads one FHIR resource as a graph and creates only the selected mappings:
 * Reference, Attachment.url, Extension, baseDefinition, meta.profile and Coding.
 */
public class Resource {
    public static final Uniqueness UNIQUENESS = Uniqueness.RELATIONSHIP_PATH;
    public static final boolean BFS = true;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LITERAL_REFERENCE =
            Pattern.compile("^([A-Z][A-Za-z0-9]+)/([^/]+)$");

    private static final String LABEL_RESOURCE = "resource";
    private static final String LABEL_FHIR_RESOURCE = "FHIRResource";
    private static final String LABEL_FHIR_ELEMENT = "FHIR_ELEMENT";
    private static final String LABEL_REFERENCE = "Reference";
    private static final String LABEL_IDENTIFIER = "Identifier";
    private static final String LABEL_EXTENSION = "Extension";
    private static final String LABEL_CODING = "Coding";
    private static final String LABEL_META = "Meta";
    private static final String LABEL_CODEABLE_CONCEPT = "CodeableConcept";
    private static final String LABEL_BACKBONE = "BackboneElement";
    private static final String LABEL_EMBEDDED_RESOURCE = "EmbeddedResource";

    private static final String REL_RESOLVES_TO = "RESOLVES_TO";
    private static final String REL_DEFINED_BY = "DEFINED_BY";
    private static final String REL_BASE_DEFINITION = "BASE_DEFINITION";
    private static final String REL_CONFORMS_TO = "CONFORMS_TO";

    private static final Set<String> MANAGED_RELATIONSHIPS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    REL_RESOLVES_TO,
                    REL_DEFINED_BY,
                    REL_BASE_DEFINITION,
                    REL_CONFORMS_TO)));

    /* All target searches use these parameterized Cypher queries. */
    private static final String QUERY_RESOURCE =
            "MATCH (r:FHIRResource {resourceType: $resourceType, id: $id}) " +
            "RETURN r LIMIT 2";

    private static final String QUERY_IDENTIFIER =
            "MATCH (r:FHIRResource)-[:identifier]->" +
            "(i:Identifier {system: $system, value: $value}) " +
            "WHERE $resourceType IS NULL OR r.resourceType = $resourceType " +
            "RETURN DISTINCT r LIMIT 2";

    private static final String QUERY_CANONICAL =
            "MATCH (r:FHIRResource {resourceType: $resourceType, url: $url}) " +
            "RETURN r LIMIT 2";

    private static final PendingReferenceCache PENDING_REFERENCES =
            new PendingReferenceCache();

    public static Validator validator;

    @Context
    public GraphDatabaseService db;

    public Resource() throws Exception {
        validator = new Validator();
    }

    @Procedure(name = "cyfhir.resource.load", mode = Mode.WRITE)
    @Description("Loads one non-Bundle FHIR resource and resolves selected mappings.")
    public Stream<MapResult> load(
            @Name("json") String json,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) throws Exception {

        Map<String, Object> resource = stringToMap(json);
        String resourceType = requiredString(resource, "resourceType");
        if ("Bundle".equals(resourceType)) {
            throw new IllegalArgumentException("Use cyfhir.bundle.load for Bundle resources");
        }

        validateFHIR(json, resourceType, config);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        try (Transaction tx = db.beginTx()) {
            WriteResult result = writeResource(resource, tx);
            ResolutionStats stats = resolveLoadedResource(tx, result, config);

            response.put("resourceKey", result.resourceKey);
            response.put("created", result.created);
            response.put("referencesResolved", stats.referencesResolved);
            response.put("referencesPending", stats.referencesPending);
            response.put("referencesAmbiguous", stats.referencesAmbiguous);
            response.put("extensionRelationships", stats.extensionRelationships);
            response.put("canonicalRelationships", stats.canonicalRelationships);
            response.put("attachmentRelationships", stats.attachmentRelationships);
            response.put("codingRelationships", stats.codingRelationships);

            tx.commit();
        }
        return Stream.of(new MapResult(response));
    }

    /** Rebuilds only the selected mappings and reconstructs the reference cache. */
    @Procedure(name = "cyfhir.resource.resolve", mode = Mode.WRITE)
    @Description("Rebuilds selected FHIR mappings without changing structural relationships.")
    public Stream<MapResult> resolve() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();

        try (Transaction tx = db.beginTx()) {
            PENDING_REFERENCES.clear();
            deleteManagedRelationships(tx);

            ResolutionStats total = new ResolutionStats();
            for (Node root : findAllResourceRoots(tx)) {
                WriteResult existing = new WriteResult(
                        root,
                        resourceKey(root),
                        false,
                        Collections.<Long>emptySet());
                total.add(resolveLoadedResource(
                        tx,
                        existing,
                        Collections.<String, Object>emptyMap()));
            }

            response.put("referencesResolved", total.referencesResolved);
            response.put("referencesPending", total.referencesPending);
            response.put("referencesAmbiguous", total.referencesAmbiguous);
            response.put("extensionRelationships", total.extensionRelationships);
            response.put("canonicalRelationships", total.canonicalRelationships);
            response.put("attachmentRelationships", total.attachmentRelationships);
            response.put("codingRelationships", total.codingRelationships);

            tx.commit();
        }
        return Stream.of(new MapResult(response));
    }

    /** Package-visible for Bundle loader reuse. */
    WriteResult writeResource(Map<String, Object> resource, Transaction tx) {
        String resourceType = requiredString(resource, "resourceType");
        String id = requiredString(resource, "id");

        Node root = findSingle(tx, QUERY_RESOURCE, params(
                "resourceType", resourceType,
                "id", id));

        boolean created = root == null;
        Set<Long> referencesToRecheck = new LinkedHashSet<Long>();

        if (created) {
            root = tx.createNode(
                    Label.label(LABEL_RESOURCE),
                    Label.label(LABEL_FHIR_RESOURCE),
                    Label.label(resourceType));
        } else {
            referencesToRecheck.addAll(incomingReferenceIds(root));
            clearResource(root);
        }

        root.setProperty("resourceType", resourceType);
        root.setProperty("id", id);
        writeObject(root, resource, tx);

        return new WriteResult(
                root,
                resourceType + "/" + id,
                created,
                referencesToRecheck);
    }

    /** Package-visible for Bundle loader reuse. */
    ResolutionStats resolveLoadedResource(
            Transaction tx,
            WriteResult result,
            Map<String, Object> config) {

        ResolutionStats stats = new ResolutionStats();
        List<Node> graph = structuralGraph(result.resourceNode);

        for (Node node : graph) {
            if (node.hasLabel(Label.label(LABEL_REFERENCE))) {
                resolveReference(tx, node, stats);
            }
        }

        for (Long referenceId : result.referencesToRecheck) {
            resolveReferenceById(tx, referenceId.longValue(), stats);
        }

        resolvePendingReferences(tx, result.resourceNode, stats);
        resolveExtensions(tx, graph, stats);
        resolveAttachmentUrls(tx, graph, stats);
        resolveBaseDefinition(tx, result.resourceNode, stats);
        resolveMetaProfiles(tx, result.resourceNode, graph, stats);
        resolveCodings(tx, graph, stats);

        return stats;
    }

    /* ---------------------------- Reference mapping ---------------------------- */

    private void resolveReference(Transaction tx, Node reference, ResolutionStats stats) {
        removeOutgoing(reference, REL_RESOLVES_TO);
        PENDING_REFERENCES.removeReference(reference.getId());

        LiteralTarget literal = LiteralTarget.parse(stringProperty(reference, "reference"));
        String targetType = normalizeType(stringProperty(reference, "type"));
        IdentifierTarget identifier = readIdentifierTarget(reference, targetType);

        List<Node> literalMatches = literal == null
                ? Collections.<Node>emptyList()
                : findMany(tx, QUERY_RESOURCE, params(
                        "resourceType", literal.resourceType,
                        "id", literal.id));

        List<Node> identifierMatches = identifier == null
                ? Collections.<Node>emptyList()
                : findMany(tx, QUERY_IDENTIFIER, params(
                        "resourceType", identifier.resourceType,
                        "system", identifier.system,
                        "value", identifier.value));

        if (!literalMatches.isEmpty()
                && !identifierMatches.isEmpty()
                && !sameNodes(literalMatches, identifierMatches)) {
            cacheReference(reference, literal, identifier);
            stats.referencesAmbiguous++;
            return;
        }

        List<Node> matches = !literalMatches.isEmpty()
                ? literalMatches
                : identifierMatches;

        if (matches.size() == 1) {
            createUniqueRelationship(reference, matches.get(0), REL_RESOLVES_TO);
            stats.referencesResolved++;
            return;
        }

        cacheReference(reference, literal, identifier);
        if (matches.isEmpty()) {
            stats.pendingReference();
        } else {
            stats.referencesAmbiguous++;
        }
    }

    private void cacheReference(
            Node reference,
            LiteralTarget literal,
            IdentifierTarget identifier) {

        if (literal != null) {
            PENDING_REFERENCES.add(literal.cacheKey(), reference.getId());
        }
        if (identifier != null) {
            PENDING_REFERENCES.add(identifier.cacheKey(), reference.getId());
        }
    }

    private void resolvePendingReferences(
            Transaction tx,
            Node target,
            ResolutionStats stats) {

        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        String resourceType = stringProperty(target, "resourceType");
        String id = stringProperty(target, "id");

        keys.add(LiteralTarget.cacheKey(resourceType, id));

        for (Node identifier : outgoingChildren(target, "identifier")) {
            String system = stringProperty(identifier, "system");
            String value = stringProperty(identifier, "value");
            if (isBlank(system) || isBlank(value)) {
                continue;
            }
            keys.add(IdentifierTarget.cacheKey(resourceType, system, value));
            keys.add(IdentifierTarget.cacheKey(null, system, value));
        }

        for (String key : keys) {
            for (Long referenceId : PENDING_REFERENCES.take(key)) {
                resolveReferenceById(tx, referenceId.longValue(), stats);
            }
        }
    }

    private void resolveReferenceById(
            Transaction tx,
            long referenceId,
            ResolutionStats stats) {
        try {
            Node reference = tx.getNodeById(referenceId);
            if (reference.hasLabel(Label.label(LABEL_REFERENCE))) {
                resolveReference(tx, reference, stats);
            }
        } catch (NotFoundException ignored) {
            PENDING_REFERENCES.removeReference(referenceId);
        }
    }

    private IdentifierTarget readIdentifierTarget(Node reference, String targetType) {
        Node identifier = outgoingChild(reference, "identifier");
        if (identifier == null) {
            return null;
        }

        String system = stringProperty(identifier, "system");
        String value = stringProperty(identifier, "value");
        if (isBlank(system) || isBlank(value)) {
            return null;
        }
        return new IdentifierTarget(targetType, system, value);
    }

    /* ---------------- Extension, canonical and coding mappings ---------------- */

    private void resolveExtensions(
            Transaction tx,
            List<Node> graph,
            ResolutionStats stats) {

        for (Node extension : graph) {
            if (!extension.hasLabel(Label.label(LABEL_EXTENSION))) {
                continue;
            }

            String url = canonicalUrl(stringProperty(extension, "url"));
            Node definition = findCanonical(tx, "StructureDefinition", url);
            if (definition != null) {
                createUniqueRelationship(extension, definition, REL_DEFINED_BY);
                stats.extensionRelationships++;
            }
        }
    }

    private void resolveBaseDefinition(
            Transaction tx,
            Node resource,
            ResolutionStats stats) {

        if (!"StructureDefinition".equals(stringProperty(resource, "resourceType"))) {
            return;
        }

        String url = canonicalUrl(stringProperty(resource, "baseDefinition"));
        Node target = findCanonical(tx, "StructureDefinition", url);
        if (target != null) {
            createUniqueRelationship(resource, target, REL_BASE_DEFINITION);
            stats.canonicalRelationships++;
        }
    }

    private void resolveMetaProfiles(
            Transaction tx,
            Node resource,
            List<Node> graph,
            ResolutionStats stats) {

        for (Node node : graph) {
            if (!node.hasLabel(Label.label(LABEL_META))) {
                continue;
            }

            for (String rawProfile : stringValues(node.getProperty("profile", null))) {
                Node profile = findCanonical(
                        tx,
                        "StructureDefinition",
                        canonicalUrl(rawProfile));
                if (profile != null) {
                    createUniqueRelationship(resource, profile, REL_CONFORMS_TO);
                    stats.canonicalRelationships++;
                }
            }
        }
    }

    private void resolveCodings(
            Transaction tx,
            List<Node> graph,
            ResolutionStats stats) {

        for (Node coding : graph) {
            if (!coding.hasLabel(Label.label(LABEL_CODING))) {
                continue;
            }

            String system = canonicalUrl(stringProperty(coding, "system"));
            Node codeSystem = findCanonical(tx, "CodeSystem", system);
            if (codeSystem != null) {
                createUniqueRelationship(coding, codeSystem, REL_DEFINED_BY);
                stats.codingRelationship();
            }
        }
    }

    private Node findCanonical(
            Transaction tx,
            String resourceType,
            String url) {

        if (isBlank(url)) {
            return null;
        }
        return findSingle(tx, QUERY_CANONICAL, params(
                "resourceType", resourceType,
                "url", url));
    }

    private void resolveAttachmentUrls(
            Transaction tx,
            List<Node> graph,
            ResolutionStats stats) {

        for (Node node : graph) {
            String binaryId = binaryIdFromAttachmentUrl(stringProperty(node, "url"));
            if (isBlank(binaryId)) {
                continue;
            }

            List<Node> matches = findMany(tx, QUERY_RESOURCE, params(
                    "resourceType", "Binary",
                    "id", binaryId));

            if (matches.size() == 1) {
                createUniqueRelationship(node, matches.get(0), REL_RESOLVES_TO);
                stats.attachmentRelationships++;
            }
        }
    }

    /* ------------------------------ Graph writer ------------------------------ */

    @SuppressWarnings("unchecked")
    private void writeObject(
            Node parent,
            Map<String, Object> object,
            Transaction tx) {

        for (Map.Entry<String, Object> field : object.entrySet()) {
            String name = field.getKey();
            Object value = field.getValue();

            if (value == null || "resourceType".equals(name) || "id".equals(name)) {
                continue;
            }

            if (value instanceof Map) {
                writeComplex(parent, name, (Map<String, Object>) value, tx);
                continue;
            }

            if (value instanceof List) {
                List<?> values = (List<?>) value;
                if (values.isEmpty()) {
                    continue;
                }

                if (containsMap(values)) {
                    ensureMapOnly(values, name);
                    for (Object item : values) {
                        if (item instanceof Map) {
                            writeComplex(parent, name, (Map<String, Object>) item, tx);
                        }
                    }
                } else {
                    Object array = toNeo4jArray(values);
                    if (array != null) {
                        parent.setProperty(name, array);
                    }
                }
                continue;
            }

            parent.setProperty(name, toNeo4jScalar(value));
        }
    }

    private void writeComplex(
            Node parent,
            String fieldName,
            Map<String, Object> value,
            Transaction tx) {

        if (value.containsKey("resourceType")) {
            writeEmbeddedResource(parent, fieldName, value, tx);
            return;
        }

        String fhirType = inferType(fieldName, value);
        Node child = tx.createNode(
                Label.label(LABEL_FHIR_ELEMENT),
                Label.label(fieldName),
                Label.label(fhirType));

        parent.createRelationshipTo(child, RelationshipType.withName(fieldName));
        writeObject(child, value, tx);
    }

    private void writeEmbeddedResource(
            Node parent,
            String fieldName,
            Map<String, Object> value,
            Transaction tx) {

        String resourceType = requiredString(value, "resourceType");
        Node child = tx.createNode(
                Label.label(LABEL_RESOURCE),
                Label.label(LABEL_EMBEDDED_RESOURCE),
                Label.label(resourceType));

        child.setProperty("resourceType", resourceType);
        String id = stringValue(value.get("id"));
        if (!isBlank(id)) {
            child.setProperty("id", id);
        }

        parent.createRelationshipTo(child, RelationshipType.withName(fieldName));
        writeObject(child, value, tx);
    }

    private String inferType(String fieldName, Map<String, Object> value) {
        if ("meta".equals(fieldName)) {
            return LABEL_META;
        }
        if ("identifier".equals(fieldName)) {
            return LABEL_IDENTIFIER;
        }
        if ("extension".equals(fieldName) || "modifierExtension".equals(fieldName)) {
            return LABEL_EXTENSION;
        }
        if ("coding".equals(fieldName) || isCoding(value)) {
            return LABEL_CODING;
        }
        if (value.containsKey("reference") || isLogicalReference(value)) {
            return LABEL_REFERENCE;
        }
        if (value.containsKey("coding")) {
            return LABEL_CODEABLE_CONCEPT;
        }
        return LABEL_BACKBONE;
    }

    private static boolean isLogicalReference(Map<String, Object> value) {
        return value.containsKey("identifier")
                && value.keySet().stream().allMatch(
                        key -> Arrays.asList(
                                "id", "extension", "reference", "type",
                                "identifier", "display").contains(key));
    }

    private static boolean isCoding(Map<String, Object> value) {
        return value.containsKey("system") && value.containsKey("code");
    }

    /* ---------------------------- Update and cleanup --------------------------- */

    private void clearResource(Node root) {
        List<Node> descendants = structuralGraph(root);
        descendants.remove(root);

        for (Node node : descendants) {
            if (node.hasLabel(Label.label(LABEL_REFERENCE))) {
                PENDING_REFERENCES.removeReference(node.getId());
            }
        }

        for (Node node : descendants) {
            for (Relationship relationship : relationshipList(node)) {
                relationship.delete();
            }
        }

        for (Node node : descendants) {
            try {
                node.delete();
            } catch (NotFoundException ignored) {
                // Already deleted with another descendant relationship cleanup.
            }
        }

        removeManagedOutgoing(root);
        clearPropertiesExcept(root, new HashSet<String>(Arrays.asList("resourceType", "id")));
    }

    private List<Node> structuralGraph(Node root) {
        LinkedHashSet<Node> visited = new LinkedHashSet<Node>();
        ArrayDeque<Node> queue = new ArrayDeque<Node>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }

            for (Relationship relationship : current.getRelationships(Direction.OUTGOING)) {
                Node child = relationship.getEndNode();
                if (child.hasLabel(Label.label(LABEL_FHIR_ELEMENT))
                        || child.hasLabel(Label.label(LABEL_EMBEDDED_RESOURCE))) {
                    queue.addLast(child);
                }
            }
        }
        return new ArrayList<Node>(visited);
    }

    private Set<Long> incomingReferenceIds(Node target) {
        LinkedHashSet<Long> ids = new LinkedHashSet<Long>();
        RelationshipType type = RelationshipType.withName(REL_RESOLVES_TO);
        for (Relationship relationship : target.getRelationships(Direction.INCOMING, type)) {
            ids.add(Long.valueOf(relationship.getStartNode().getId()));
        }
        return ids;
    }

    private void deleteManagedRelationships(Transaction tx) {
        List<Relationship> delete = new ArrayList<Relationship>();
        for (Node node : allNodes(tx)) {
            for (Relationship relationship : node.getRelationships(Direction.OUTGOING)) {
                if (MANAGED_RELATIONSHIPS.contains(relationship.getType().name())) {
                    delete.add(relationship);
                }
            }
        }
        for (Relationship relationship : delete) {
            relationship.delete();
        }
    }

    private void removeManagedOutgoing(Node node) {
        List<Relationship> delete = new ArrayList<Relationship>();
        for (Relationship relationship : node.getRelationships(Direction.OUTGOING)) {
            if (MANAGED_RELATIONSHIPS.contains(relationship.getType().name())) {
                delete.add(relationship);
            }
        }
        for (Relationship relationship : delete) {
            relationship.delete();
        }
    }

    /* ------------------------------- Cypher access ------------------------------ */

    private Node findSingle(
            Transaction tx,
            String query,
            Map<String, Object> parameters) {

        List<Node> nodes = findMany(tx, query, parameters);
        return nodes.size() == 1 ? nodes.get(0) : null;
    }

    private List<Node> findMany(
            Transaction tx,
            String query,
            Map<String, Object> parameters) {

        List<Node> nodes = new ArrayList<Node>();
        try (Result result = tx.execute(query, parameters)) {
            while (result.hasNext()) {
                Object node = result.next().get("r");
                if (node instanceof Node) {
                    nodes.add((Node) node);
                }
            }
        }
        return distinctNodes(nodes);
    }

    private List<Node> findAllResourceRoots(Transaction tx) {
        List<Node> result = new ArrayList<Node>();
        ResourceIterator<Node> iterator = tx.findNodes(Label.label(LABEL_FHIR_RESOURCE));
        try {
            while (iterator.hasNext()) {
                result.add(iterator.next());
            }
        } finally {
            iterator.close();
        }
        return result;
    }

    /* -------------------------------- Validation -------------------------------- */

    public IAnyResource validateFHIR(
            String json,
            String resourceType,
            Map<String, Object> config) throws Exception {

        if (!Boolean.TRUE.equals(config.get("validation"))) {
            return null;
        }

        Object version = config.get("version");
        Validator selected = version == null
                ? validator
                : new Validator(version.toString());
        return selected.validate(json, resourceType);
    }

    public Map<String, Object> stringToMap(
            @Name("jsonString") String jsonString) throws IOException {
        return JSON.readValue(jsonString, Map.class);
    }

    /* --------------------------------- Helpers --------------------------------- */

    private static void createUniqueRelationship(
            Node source,
            Node target,
            String relationshipName) {

        RelationshipType type = RelationshipType.withName(relationshipName);
        for (Relationship relationship : source.getRelationships(Direction.OUTGOING, type)) {
            if (relationship.getEndNode().equals(target)) {
                return;
            }
        }
        source.createRelationshipTo(target, type);
    }

    private static void removeOutgoing(Node node, String relationshipName) {
        List<Relationship> delete = new ArrayList<Relationship>();
        RelationshipType type = RelationshipType.withName(relationshipName);
        for (Relationship relationship : node.getRelationships(Direction.OUTGOING, type)) {
            delete.add(relationship);
        }
        for (Relationship relationship : delete) {
            relationship.delete();
        }
    }

    private static Node outgoingChild(Node parent, String relationshipName) {
        for (Relationship relationship : parent.getRelationships(
                Direction.OUTGOING,
                RelationshipType.withName(relationshipName))) {
            return relationship.getEndNode();
        }
        return null;
    }

    private static List<Node> outgoingChildren(Node parent, String relationshipName) {
        List<Node> result = new ArrayList<Node>();
        for (Relationship relationship : parent.getRelationships(
                Direction.OUTGOING,
                RelationshipType.withName(relationshipName))) {
            result.add(relationship.getEndNode());
        }
        return result;
    }

    private static List<Relationship> relationshipList(Node node) {
        List<Relationship> result = new ArrayList<Relationship>();
        for (Relationship relationship : node.getRelationships()) {
            result.add(relationship);
        }
        return result;
    }

    private static List<Node> allNodes(Transaction tx) {
        List<Node> result = new ArrayList<Node>();
        for (Node node : tx.getAllNodes()) {
            result.add(node);
        }
        return result;
    }

    private static List<Node> distinctNodes(List<Node> nodes) {
        LinkedHashMap<Long, Node> distinct = new LinkedHashMap<Long, Node>();
        for (Node node : nodes) {
            distinct.put(Long.valueOf(node.getId()), node);
        }
        return new ArrayList<Node>(distinct.values());
    }

    private static boolean sameNodes(List<Node> left, List<Node> right) {
        Set<Long> leftIds = new HashSet<Long>();
        Set<Long> rightIds = new HashSet<Long>();
        for (Node node : left) {
            leftIds.add(Long.valueOf(node.getId()));
        }
        for (Node node : right) {
            rightIds.add(Long.valueOf(node.getId()));
        }
        return leftIds.equals(rightIds);
    }

    private static void clearPropertiesExcept(Node node, Set<String> keep) {
        List<String> remove = new ArrayList<String>();
        for (String key : node.getPropertyKeys()) {
            if (!keep.contains(key)) {
                remove.add(key);
            }
        }
        for (String key : remove) {
            node.removeProperty(key);
        }
    }

    private static String resourceKey(Node resource) {
        return stringProperty(resource, "resourceType")
                + "/"
                + stringProperty(resource, "id");
    }

    private static String canonicalUrl(String raw) {
        if (isBlank(raw)) {
            return null;
        }

        String value = raw.trim();
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        int version = value.indexOf('|');
        if (version >= 0) {
            value = value.substring(0, version);
        }
        return value;
    }

    private static String normalizeType(String rawType) {
        if (isBlank(rawType)) {
            return null;
        }
        int slash = rawType.lastIndexOf('/');
        return slash >= 0 ? rawType.substring(slash + 1) : rawType;
    }

    private static String binaryIdFromAttachmentUrl(String rawUrl) {
        if (isBlank(rawUrl)) {
            return null;
        }

        String value = rawUrl.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        int history = value.indexOf("/_history/");
        if (history >= 0) {
            value = value.substring(0, history);
        }

        if (value.startsWith("Binary/")) {
            return value.substring("Binary/".length());
        }

        int marker = value.indexOf("/Binary/");
        if (marker >= 0) {
            return value.substring(marker + "/Binary/".length());
        }

        return null;
    }

    private static Map<String, Object> params(Object... values) {
        Map<String, Object> result = new HashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index].toString(), values[index + 1]);
        }
        return result;
    }

    private static String stringProperty(Node node, String key) {
        if (node == null) {
            return null;
        }
        Object value = node.getProperty(key, null);
        return value == null ? null : value.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String requiredString(Map<String, Object> map, String key) {
        String value = stringValue(map.get(key));
        if (isBlank(value)) {
            throw new IllegalArgumentException("FHIR object is missing required field: " + key);
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean containsMap(List<?> values) {
        for (Object value : values) {
            if (value instanceof Map) {
                return true;
            }
        }
        return false;
    }

    private static void ensureMapOnly(List<?> values, String fieldName) {
        for (Object value : values) {
            if (value != null && !(value instanceof Map)) {
                throw new IllegalArgumentException(
                        "FHIR array mixes primitive and object values at " + fieldName);
            }
        }
    }

    private static Object toNeo4jScalar(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        return value.toString();
    }

    private static Object toNeo4jArray(List<?> values) {
        List<Object> nonNull = new ArrayList<Object>();
        for (Object value : values) {
            if (value != null) {
                nonNull.add(value);
            }
        }
        if (nonNull.isEmpty()) {
            return new String[0];
        }
        if (allStrings(nonNull)) {
            String[] result = new String[nonNull.size()];
            for (int index = 0; index < nonNull.size(); index++) {
                result[index] = nonNull.get(index).toString();
            }
            return result;
        }
        if (allBooleans(nonNull)) {
            boolean[] result = new boolean[nonNull.size()];
            for (int index = 0; index < nonNull.size(); index++) {
                result[index] = (Boolean) nonNull.get(index);
            }
            return result;
        }
        if (allIntegral(nonNull)) {
            long[] result = new long[nonNull.size()];
            for (int index = 0; index < nonNull.size(); index++) {
                result[index] = ((Number) nonNull.get(index)).longValue();
            }
            return result;
        }
        if (allNumbers(nonNull)) {
            double[] result = new double[nonNull.size()];
            for (int index = 0; index < nonNull.size(); index++) {
                result[index] = ((Number) nonNull.get(index)).doubleValue();
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported primitive array: " + values);
    }

    private static boolean allStrings(List<Object> values) {
        for (Object value : values) {
            if (!(value instanceof String || value instanceof Character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allBooleans(List<Object> values) {
        for (Object value : values) {
            if (!(value instanceof Boolean)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allIntegral(List<Object> values) {
        for (Object value : values) {
            if (!(value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allNumbers(List<Object> values) {
        for (Object value : values) {
            if (!(value instanceof Number)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> stringValues(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object item = java.lang.reflect.Array.get(value, index);
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }

        return Collections.singletonList(value.toString());
    }

    /* ------------------------------- APOC expand ------------------------------- */

    @Procedure(name = "cyfhir.resource.expand", mode = Mode.READ)
    @Description("Expands paths from a FHIR resource node.")
    public Stream<PathResult> expand(@Name("start") Object start) throws Exception {
        try (Transaction tx = db.beginTx()) {
            List<Node> nodes = startToNodes(start, tx);
            List<PathResult> results = new ArrayList<PathResult>();
            explorePathPrivate(
                    nodes, ">", null, 0, 100, BFS, UNIQUENESS,
                    false, -1, null, null, true, tx)
                    .forEach(path -> results.add(new PathResult(path)));
            tx.commit();
            return results.stream();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Node> startToNodes(Object start, Transaction tx) throws Exception {
        if (start == null) {
            return Collections.emptyList();
        }
        if (start instanceof Node) {
            return Collections.singletonList((Node) start);
        }
        if (start instanceof Number) {
            return Collections.singletonList(tx.getNodeById(((Number) start).longValue()));
        }
        if (start instanceof List) {
            List<?> list = (List<?>) start;
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
            if (list.get(0) instanceof Node) {
                return (List<Node>) list;
            }
            if (list.get(0) instanceof Number) {
                List<Node> nodes = new ArrayList<Node>();
                for (Object value : list) {
                    nodes.add(tx.getNodeById(((Number) value).longValue()));
                }
                return nodes;
            }
        }
        throw new Exception("start must be a Node, node id, list of Nodes, or list of node ids");
    }

    private Stream<Path> explorePathPrivate(
            Iterable<Node> startNodes,
            String pathFilter,
            String labelFilter,
            long minLevel,
            long maxLevel,
            boolean bfs,
            Uniqueness uniqueness,
            boolean filterStartNode,
            long limit,
            EnumMap<NodeFilter, List<Node>> nodeFilter,
            String sequence,
            boolean beginSequenceAtStart,
            Transaction tx) {

        Traverser traverser = traverse(
                tx.traversalDescription(),
                startNodes,
                pathFilter,
                labelFilter,
                minLevel,
                maxLevel,
                uniqueness,
                bfs,
                filterStartNode,
                nodeFilter,
                sequence,
                beginSequenceAtStart);

        Stream<Path> stream = Iterables.stream(traverser);
        return limit == -1 ? stream : stream.limit(limit);
    }

    public Traverser traverse(
            TraversalDescription traversalDescription,
            Iterable<Node> startNodes,
            String pathFilter,
            String labelFilter,
            long minLevel,
            long maxLevel,
            Uniqueness uniqueness,
            boolean bfs,
            boolean filterStartNode,
            EnumMap<NodeFilter, List<Node>> nodeFilter,
            String sequence,
            boolean beginSequenceAtStart) {

        TraversalDescription description = bfs
                ? traversalDescription.breadthFirst()
                : traversalDescription.depthFirst();

        if (sequence != null && !sequence.trim().isEmpty()) {
            String[] steps = sequence.split(",");
            List<String> labels = new ArrayList<String>();
            List<String> relationships = new ArrayList<String>();

            for (int index = 0; index < steps.length; index++) {
                List<String> destination =
                        (beginSequenceAtStart ? index : index - 1) % 2 == 0
                                ? labels
                                : relationships;
                destination.add(steps[index]);
            }

            description = description.expand(
                    new RelationshipSequenceExpander(relationships, beginSequenceAtStart));
            description = description.evaluator(
                    new LabelSequenceEvaluator(
                            labels,
                            filterStartNode,
                            beginSequenceAtStart,
                            (int) minLevel));
        } else {
            if (pathFilter != null && !pathFilter.trim().isEmpty()) {
                description = description.expand(
                        new RelationshipSequenceExpander(
                                pathFilter.trim(),
                                beginSequenceAtStart));
            }
            if (labelFilter != null && !labelFilter.trim().isEmpty()) {
                description = description.evaluator(
                        new LabelSequenceEvaluator(
                                labelFilter.trim(),
                                filterStartNode,
                                beginSequenceAtStart,
                                (int) minLevel));
            }
        }

        if (minLevel != -1) {
            description = description.evaluator(Evaluators.fromDepth((int) minLevel));
        }
        if (maxLevel != -1) {
            description = description.evaluator(Evaluators.toDepth((int) maxLevel));
        }

        if (nodeFilter != null && !nodeFilter.isEmpty()) {
            List<Node> endNodes = nodeFilter.getOrDefault(
                    NodeFilter.END_NODES,
                    Collections.<Node>emptyList());
            List<Node> terminatorNodes = nodeFilter.getOrDefault(
                    NodeFilter.TERMINATOR_NODES,
                    Collections.<Node>emptyList());
            List<Node> blacklistNodes = nodeFilter.getOrDefault(
                    NodeFilter.BLACKLIST_NODES,
                    Collections.<Node>emptyList());
            List<Node> whitelistNodes = nodeFilter.containsKey(NodeFilter.WHITELIST_NODES)
                    ? new ArrayList<Node>(nodeFilter.get(NodeFilter.WHITELIST_NODES))
                    : new ArrayList<Node>();

            if (!blacklistNodes.isEmpty()) {
                description = description.evaluator(
                        NodeEvaluators.blacklistNodeEvaluator(
                                filterStartNode,
                                (int) minLevel,
                                blacklistNodes));
            }

            Evaluator evaluator = NodeEvaluators.endAndTerminatorNodeEvaluator(
                    filterStartNode,
                    (int) minLevel,
                    endNodes,
                    terminatorNodes);
            if (evaluator != null) {
                description = description.evaluator(evaluator);
            }

            if (!whitelistNodes.isEmpty()) {
                whitelistNodes.addAll(endNodes);
                whitelistNodes.addAll(terminatorNodes);
                description = description.evaluator(
                        NodeEvaluators.whitelistNodeEvaluator(
                                filterStartNode,
                                (int) minLevel,
                                whitelistNodes));
            }
        }

        return description.uniqueness(uniqueness).traverse(startNodes);
    }

    enum NodeFilter {
        WHITELIST_NODES,
        BLACKLIST_NODES,
        END_NODES,
        TERMINATOR_NODES
    }

    static final class WriteResult {
        final Node resourceNode;
        final String resourceKey;
        final boolean created;
        final Set<Long> referencesToRecheck;

        WriteResult(Node resourceNode, String resourceKey, boolean created) {
            this(resourceNode, resourceKey, created, Collections.<Long>emptySet());
        }

        WriteResult(
                Node resourceNode,
                String resourceKey,
                boolean created,
                Set<Long> referencesToRecheck) {
            this.resourceNode = resourceNode;
            this.resourceKey = resourceKey;
            this.created = created;
            this.referencesToRecheck = referencesToRecheck;
        }
    }

    static final class ResolutionStats {
        long referencesResolved;
        long referencesPending;
        long referencesUnresolved;      // Compatibility alias for Bundle code.
        long referencesAmbiguous;
        long extensionRelationships;
        long canonicalRelationships;
        long attachmentRelationships;
        long codingRelationships;
        long terminologyRelationships;  // Compatibility alias for Bundle code.

        void pendingReference() {
            referencesPending++;
            referencesUnresolved++;
        }

        void codingRelationship() {
            codingRelationships++;
            terminologyRelationships++;
        }

        void add(ResolutionStats other) {
            referencesResolved += other.referencesResolved;
            referencesPending += other.referencesPending;
            referencesUnresolved += other.referencesUnresolved;
            referencesAmbiguous += other.referencesAmbiguous;
            extensionRelationships += other.extensionRelationships;
            canonicalRelationships += other.canonicalRelationships;
            attachmentRelationships += other.attachmentRelationships;
            codingRelationships += other.codingRelationships;
            terminologyRelationships += other.terminologyRelationships;
        }
    }

    private static final class LiteralTarget {
        final String resourceType;
        final String id;

        LiteralTarget(String resourceType, String id) {
            this.resourceType = resourceType;
            this.id = id;
        }

        static LiteralTarget parse(String raw) {
            if (isBlank(raw)) {
                return null;
            }
            Matcher matcher = LITERAL_REFERENCE.matcher(raw.trim());
            if (!matcher.matches()) {
                return null;
            }
            return new LiteralTarget(matcher.group(1), matcher.group(2));
        }

        String cacheKey() {
            return cacheKey(resourceType, id);
        }

        static String cacheKey(String resourceType, String id) {
            return "RESOURCE|" + resourceType + "|" + id;
        }
    }

    private static final class IdentifierTarget {
        final String resourceType;
        final String system;
        final String value;

        IdentifierTarget(String resourceType, String system, String value) {
            this.resourceType = resourceType;
            this.system = system;
            this.value = value;
        }

        String cacheKey() {
            return cacheKey(resourceType, system, value);
        }

        static String cacheKey(String resourceType, String system, String value) {
            return "IDENTIFIER|"
                    + (isBlank(resourceType) ? "*" : resourceType)
                    + "|" + system
                    + "|" + value;
        }
    }

    /** Keeps unresolved references indexed by target and by reference id. */
    private static final class PendingReferenceCache {
        private final ConcurrentMap<String, Set<Long>> byTarget =
                new ConcurrentHashMap<String, Set<Long>>();
        private final ConcurrentMap<Long, Set<String>> byReference =
                new ConcurrentHashMap<Long, Set<String>>();

        void add(String targetKey, long referenceId) {
            if (isBlank(targetKey)) {
                return;
            }

            Long id = Long.valueOf(referenceId);
            byTarget.computeIfAbsent(
                    targetKey,
                    ignored -> ConcurrentHashMap.newKeySet()).add(id);
            byReference.computeIfAbsent(
                    id,
                    ignored -> ConcurrentHashMap.newKeySet()).add(targetKey);
        }

        Set<Long> take(String targetKey) {
            Set<Long> ids = byTarget.remove(targetKey);
            if (ids == null) {
                return Collections.emptySet();
            }

            for (Long id : ids) {
                Set<String> keys = byReference.get(id);
                if (keys != null) {
                    keys.remove(targetKey);
                    if (keys.isEmpty()) {
                        byReference.remove(id, keys);
                    }
                }
            }
            return new LinkedHashSet<Long>(ids);
        }

        void removeReference(long referenceId) {
            Long id = Long.valueOf(referenceId);
            Set<String> keys = byReference.remove(id);
            if (keys == null) {
                return;
            }

            for (String key : keys) {
                Set<Long> ids = byTarget.get(key);
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) {
                        byTarget.remove(key, ids);
                    }
                }
            }
        }

        void clear() {
            byTarget.clear();
            byReference.clear();
        }
    }
}
