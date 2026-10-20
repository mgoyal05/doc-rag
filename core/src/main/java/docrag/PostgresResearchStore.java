package docrag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * JDBC persistence. Connections/transactions never span extraction, embedding or generation calls.
 */
public final class PostgresResearchStore {
    private final DataSource source;
    private final String schema;
    private final int dimensions;
    private final ObjectMapper json = new ObjectMapper();

    public PostgresResearchStore(DataSource source, String schema, String model, int dimensions) throws Exception {
        this.source = Objects.requireNonNull(source);
        this.schema = ResearchSchema.identifier(schema);
        this.dimensions = dimensions;
        try (var connection = connection(); var statement = connection.createStatement();
             var row = statement.executeQuery("select * from " + schema + ".profile where singleton")) {
            if (!row.next() || row.getInt("schema_version") != 1 || row.getInt("dimensions") != dimensions
                    || !row.getString("embedding_model").equals(model))
                throw new SQLException("Research schema '" + schema + "' has an incompatible schema version or embedding profile. "
                        + "Use the original embedding model/dimensions or explicitly provision a separate schema; existing data was not changed.", "22023");
        } catch (SQLException e) {
            if ("22023".equals(e.getSQLState())) throw e;
            String help = "42P01".equals(e.getSQLState())
                    ? "Research schema '" + schema + "' is not initialized in this DataSource. Install pgvector >= 0.8, then explicitly "
                    + "set research.initialize-schema=true for first setup (or call ResearchSchema.initialize). No setup runs by default."
                    : "Cannot read research schema '" + schema + "' (SQLSTATE " + e.getSQLState() + "). "
                    + "Check database connectivity, schema permissions and pgvector setup; existing data was not changed.";
            throw new SQLException(help, e.getSQLState(), e);
        }
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Research cancelled");
    }

    private Connection connection() throws SQLException {
        return source.getConnection();
    }

    private PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(15);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof String[] array) statement.setArray(i + 1, connection.createArrayOf("text", array));
            else statement.setObject(i + 1, value);
        }
        return statement;
    }

    private int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = statement(connection, sql, values)) {
            return statement.executeUpdate();
        }
    }

    public DocumentRow document(String id) throws Exception {
        try (var connection = connection(); var statement = statement(connection, "select * from " + schema + ".documents where id=?", id);
             var row = statement.executeQuery()) {
            return row.next() ? document(row) : null;
        }
    }

    public DocumentRow duplicate(String kind, String fingerprint) throws Exception {
        try (var connection = connection(); var statement = statement(connection,
                "select * from " + schema + ".documents where kind=? and fingerprint=? order by updated_at desc limit 1", kind, fingerprint);
             var row = statement.executeQuery()) {
            return row.next() ? document(row) : null;
        }
    }

    public List<DocumentRow> catalog() throws Exception {
        List<DocumentRow> result = new ArrayList<>();
        try (var connection = connection(); var statement = statement(connection,
                "select id,kind,company,period_keys,metadata_json,source,file_path,fingerprint,active_version,state,null::text as draft_json from "
                        + schema + ".documents where kind='UPLOAD' order by updated_at desc limit 201"); var rows = statement.executeQuery()) {
            while (rows.next()) result.add(document(rows));
        }
        if (result.size() > 200)
            throw new IllegalStateException("Uploaded report catalog exceeds the 200-report pilot limit");
        return List.copyOf(result);
    }

    private DocumentRow document(ResultSet row) throws Exception {
        return new DocumentRow(row.getString("id"), row.getString("kind"), row.getString("company"),
                Arrays.asList((String[]) row.getArray("period_keys").getArray()), row.getString("metadata_json"),
                row.getString("source"), row.getString("file_path"), row.getString("fingerprint"), row.getString("active_version"),
                row.getString("state"), row.getString("draft_json"));
    }

    public void stage(DocumentRow document) throws Exception {
        try (var connection = connection()) {
            update(connection, "insert into " + schema + ".documents(id,kind,source,file_path,fingerprint,state,draft_json) values(?,?,?,?,?,'NEEDS_METADATA',?)",
                    document.id(), document.kind(), document.source(), document.file(), document.fingerprint(), document.draft());
        }
    }

    public void publish(DocumentRow document, List<Research.Chunk> chunks, List<float[]> vectors, String extractionVersion) throws Exception {
        if (chunks.isEmpty() || chunks.size() != vectors.size())
            throw new IllegalArgumentException("Complete chunks/vectors required");
        String version = UUID.randomUUID().toString();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                update(connection, "insert into " + schema + ".documents(id,kind,company,period_keys,metadata_json,source,file_path,fingerprint,state) "
                                + "values(?,?,?,?,?,?,?,?,'READY') on conflict(id) do nothing", document.id(), document.kind(), document.company(),
                        document.periods().toArray(String[]::new), document.metadata(), document.source(), document.file(), document.fingerprint());
                // Serialize replacement/removal of this document, without blocking unrelated reads or model work.
                try (var lock = statement(connection, "select id from " + schema + ".documents where id=? for update", document.id())) {
                    lock.executeQuery().close();
                }
                try (var insert = connection.prepareStatement("insert into " + schema + ".chunks "
                        + "(id,document_id,version,ordinal,text,embedding_text,pages_json,headings_json,tokens,extraction_version,embedding) values(?,?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setQueryTimeout(30);
                    for (int i = 0; i < chunks.size(); i++) {
                        var chunk = chunks.get(i);
                        float[] vector = vectors.get(i);
                        validate(vector);
                        insert.setString(1, document.id() + ":" + version + ":" + i);
                        insert.setString(2, document.id());
                        insert.setString(3, version);
                        insert.setInt(4, i);
                        insert.setString(5, chunk.text());
                        insert.setString(6, chunk.embeddingText());
                        insert.setString(7, json.writeValueAsString(chunk.pages()));
                        insert.setString(8, json.writeValueAsString(chunk.headings()));
                        insert.setInt(9, chunk.tokens());
                        insert.setString(10, extractionVersion);
                        insert.setObject(11, new PGvector(vector));
                        insert.addBatch();
                        if ((i + 1) % 100 == 0 || i + 1 == chunks.size()) insert.executeBatch();
                    }
                }
                interrupted();
                update(connection, "update " + schema + ".documents set company=?,period_keys=?,metadata_json=?,source=?,file_path=?,fingerprint=?,"
                                + "active_version=?,state='READY',draft_json=null,updated_at=now() where id=?", document.company(), document.periods().toArray(String[]::new),
                        document.metadata(), document.source(), document.file(), document.fingerprint(), version, document.id());
                update(connection, "delete from " + schema + ".chunks where document_id=? and version<>?", document.id(), version);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public void validate(float[] vector) {
        if (vector == null || vector.length != dimensions)
            throw new IllegalArgumentException("Embedding dimension mismatch");
        double magnitude = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
            magnitude += value * (double) value;
        }
        if (magnitude == 0) throw new IllegalArgumentException("Zero embedding");
    }

    public List<Research.Evidence> search(Research.Target target, String query, float[] vector, int k) throws Exception {
        validate(vector);
        Map<String, Hit> hits = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        String select = "select c.*,d.company,d.period_keys,d.source from " + schema + ".chunks c join " + schema
                + ".documents d on d.id=c.document_id and d.active_version=c.version where d.kind='UPLOAD' and d.company=? and ?=any(d.period_keys) ";
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (var setting = connection.createStatement()) {
                    setting.execute("set local hnsw.iterative_scan='strict_order'");
                }
                for (boolean lexical : List.of(true, false)) {
                    String sql = select + (lexical ? "and c.search_text @@ plainto_tsquery('english',?) order by ts_rank_cd(c.search_text,plainto_tsquery('english',?)) desc,c.id limit ?"
                            : "order by c.embedding <=> ? limit ?");
                    Object[] args = lexical ? new Object[]{target.company(), target.period(), query, query, Math.min(100, k * 4)}
                            : new Object[]{target.company(), target.period(), new PGvector(vector), Math.min(100, k * 4)};
                    try (var statement = statement(connection, sql, args); var rows = statement.executeQuery()) {
                        int rank = 0;
                        while (rows.next()) {
                            var hit = hit(rows, target.period());
                            hits.put(hit.evidence().id(), hit);
                            scores.merge(hit.evidence().id(), 1.0 / (60 + ++rank), Double::sum);
                        }
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
        List<Hit> ranked = scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(k).map(e -> hits.get(e.getKey())).toList();
        // One bounded batched neighbor read, restricted to the same document version and section.
        LinkedHashMap<String, Research.Evidence> result = new LinkedHashMap<>();
        for (var hit : ranked) result.put(hit.evidence().id(), hit.evidence());
        if (!ranked.isEmpty()) {
            StringJoiner clauses = new StringJoiner(" or ");
            List<Object> args = new ArrayList<>();
            for (var hit : ranked) {
                clauses.add("(c.document_id=? and c.version=? and c.ordinal between ? and ?)");
                Collections.addAll(args, hit.evidence().documentId(), hit.version(), hit.ordinal() - 1, hit.ordinal() + 1);
            }
            try (var connection = connection(); var statement = statement(connection, "select c.*,d.company,d.period_keys,d.source from " + schema
                    + ".chunks c join " + schema + ".documents d on d.id=c.document_id and d.active_version=c.version where " + clauses + " order by c.document_id,c.ordinal", args.toArray());
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var neighbor = hit(rows, target.period());
                    if (ranked.stream().anyMatch(h -> h.evidence().documentId().equals(neighbor.evidence().documentId())
                            && h.version().equals(neighbor.version()) && Math.abs(h.ordinal() - neighbor.ordinal()) <= 1
                            && h.evidence().headings().equals(neighbor.evidence().headings())))
                        result.putIfAbsent(neighbor.evidence().id(), neighbor.evidence());
                }
            }
        }
        return List.copyOf(result.values());
    }

    private Hit hit(ResultSet row, String period) throws Exception {
        List<Integer> pages = json.readValue(row.getString("pages_json"), new TypeReference<>() {
        });
        List<String> headings = json.readValue(row.getString("headings_json"), new TypeReference<>() {
        });
        return new Hit(new Research.Evidence(row.getString("id"), row.getString("document_id"), row.getString("company"), period,
                row.getString("source"), pages.getFirst(), row.getString("text"), pages, headings), row.getInt("ordinal"), row.getString("version"));
    }

    public List<Research.Evidence> documentEvidence(String id, int limit) throws Exception {
        List<Research.Evidence> result = new ArrayList<>();
        try (var connection = connection(); var statement = statement(connection, "select c.*,d.company,d.period_keys,d.source from " + schema
                + ".chunks c join " + schema + ".documents d on d.id=c.document_id and d.active_version=c.version where d.id=? order by c.ordinal limit ?", id, limit + 1);
             var rows = statement.executeQuery()) {
            while (rows.next()) result.add(hit(rows, "").evidence());
        }
        if (result.size() > limit) throw new IllegalStateException("Document chunk limit exceeded");
        return List.copyOf(result);
    }

    public void removeDocument(String id) throws Exception {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                update(connection, "delete from " + schema + ".chunks where document_id=?", id);
                update(connection, "delete from " + schema + ".documents where id=?", id);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public Conversation createConversation(String owner) throws Exception {
        Research.required(owner, "owner");
        String id = UUID.randomUUID().toString();
        try (var connection = connection()) {
            update(connection, "insert into " + schema + ".conversations(id,owner) values(?::uuid,?)", id, owner);
        }
        return conversation(owner, id);
    }

    public Conversation conversation(String owner, String id) throws Exception {
        try (var connection = connection()) {
            return owned(connection, owner, id, false);
        }
    }

    private Conversation owned(Connection connection, String owner, String id, boolean lock) throws Exception {
        UUID.fromString(id);
        Research.required(owner, "owner");
        try (var statement = statement(connection, "select * from " + schema + ".conversations where id=?::uuid and owner=?" + (lock ? " for update" : ""), id, owner);
             var row = statement.executeQuery()) {
            if (!row.next()) throw new java.nio.file.NoSuchFileException("Conversation not found");
            return conversation(row);
        }
    }

    private Conversation conversation(ResultSet row) throws SQLException {
        return new Conversation(row.getString("id"), row.getString("title"), row.getString("active_turn"), row.getString("scope_json"),
                row.getString("pending_document"), row.getString("updated_at"));
    }

    public List<Conversation> conversations(String owner) throws Exception {
        List<Conversation> result = new ArrayList<>();
        Research.required(owner, "owner");
        try (var connection = connection(); var statement = statement(connection,
                "select * from " + schema + ".conversations where owner=? order by updated_at desc limit 20", owner); var rows = statement.executeQuery()) {
            while (rows.next()) result.add(conversation(rows));
        }
        return List.copyOf(result);
    }

    public String reserve(String owner, String conversation, String kind, String text, String payload) throws Exception {
        Research.required(text, "message");
        if (text.length() > 4000) throw new IllegalArgumentException("Message exceeds 4000 characters");
        String id = UUID.randomUUID().toString();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (owned(connection, owner, conversation, true).activeTurn() != null)
                    throw new BusyConversationException();
                update(connection, "insert into " + schema + ".turns(id,conversation_id,kind,text,payload_json,state) values(?::uuid,?::uuid,?,?,?,'QUEUED')",
                        id, conversation, kind, text, payload);
                update(connection, "update " + schema + ".conversations set active_turn=?::uuid,title=case when title='New conversation' then ? else title end,updated_at=now() where id=?::uuid",
                        id, text.substring(0, Math.min(80, text.length())), conversation);
                connection.commit();
                return id;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public List<Turn> history(String owner, String conversation) throws Exception {
        conversation(owner, conversation);
        List<Turn> result = new ArrayList<>();
        try (var connection = connection(); var statement = statement(connection,
                "select * from " + schema + ".turns where conversation_id=?::uuid order by created_at desc,id desc limit 100", conversation); var rows = statement.executeQuery()) {
            while (rows.next())
                result.add(new Turn(rows.getString("id"), rows.getString("kind"), rows.getString("text"), rows.getString("state"), rows.getString("result_json"), rows.getString("created_at")));
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public String payload(String owner, String conversation, String turn) throws Exception {
        conversation(owner, conversation);
        try (var connection = connection(); var statement = statement(connection,
                "select payload_json from " + schema + ".turns where id=?::uuid and conversation_id=?::uuid", turn, conversation); var row = statement.executeQuery()) {
            if (!row.next()) throw new java.nio.file.NoSuchFileException("Turn not found");
            return row.getString(1);
        }
    }

    public void requireActive(String owner, String conversation, String turn) throws Exception {
        if (!Objects.equals(conversation(owner, conversation).activeTurn(), turn))
            throw new CancellationException("Turn is no longer active");
    }

    public void finish(String owner, String conversation, String turn, String result, String scope, String pendingDocument, String state) throws Exception {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!Objects.equals(owned(connection, owner, conversation, true).activeTurn(), turn))
                    throw new CancellationException("Turn cancelled");
                update(connection, "update " + schema + ".turns set state=?,result_json=? where id=?::uuid and conversation_id=?::uuid", state, result, turn, conversation);
                update(connection, "update " + schema + ".conversations set active_turn=null,scope_json=coalesce(?,scope_json),pending_document=?,updated_at=now() where id=?::uuid",
                        scope, pendingDocument, conversation);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public void interrupt(String owner, String conversation, String turn) throws Exception {
        try {
            finish(owner, conversation, turn, null, null, conversation(owner, conversation).pendingDocument(), "INTERRUPTED");
        } catch (
                CancellationException ignored) { /* Already terminal. Never overwrite a completed/cancelled result. */ }
    }

    /**
     * Host startup recovery, only after obtaining exclusive ownership of its worker/job store.
     */
    public void recoverInterruptedTurns() throws Exception {
        for (int batch = 0; batch < 10; batch++)
            try (var connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    List<String[]> pending = new ArrayList<>();
                    try (var query = statement(connection, "select id,active_turn from " + schema + ".conversations where active_turn is not null limit 100 for update"); var rows = query.executeQuery()) {
                        while (rows.next()) pending.add(new String[]{rows.getString(1), rows.getString(2)});
                    }
                    for (var turn : pending) {
                        update(connection, "update " + schema + ".turns set state='INTERRUPTED',result_json=null where id=?::uuid", turn[1]);
                        update(connection, "update " + schema + ".conversations set active_turn=null,updated_at=now() where id=?::uuid", turn[0]);
                    }
                    connection.commit();
                    if (pending.size() < 100) return;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            }
        throw new IllegalStateException("More than 1000 interrupted conversations; reconcile before accepting work");
    }

    public record DocumentRow(String id, String kind, String company, List<String> periods, String metadata,
                              String source, String file, String fingerprint, String version, String state,
                              String draft) {
    }

    public record Conversation(String id, String title, String activeTurn, String scope, String pendingDocument,
                               String updatedAt) {
    }

    public record Turn(String id, String kind, String text, String state, String result, String createdAt) {
    }

    private record Hit(Research.Evidence evidence, int ordinal, String version) {
    }

    public static final class BusyConversationException extends IllegalStateException {
        public BusyConversationException() {
            super("A question or upload is already active in this conversation");
        }
    }
}
