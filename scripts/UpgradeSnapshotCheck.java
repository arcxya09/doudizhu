package com.arcxya.doudizhu;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Standalone Java 17 verifier; works in the offline signing job without Android/Kotlin build output. */
public class UpgradeSnapshotCheck {
    private record Counts(int games, int wins, int score) {
        Counts {
            if (games < 0 || wins < 0 || wins > games || Math.abs((long) score) > games * 196608L)
                throw new IllegalArgumentException("Invalid saved record");
        }
    }
    private record SavedState(Game game, Counts record, boolean atomicRecord) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected before-table before-record after-table after-record");
        SavedState before = load(Path.of(args[0]), Path.of(args[1]));
        SavedState after = load(Path.of(args[2]), Path.of(args[3]));
        if (!after.atomicRecord) throw new IllegalStateException("Candidate did not commit the table and record together");
        if (!before.record.equals(after.record))
            throw new IllegalStateException("Record changed during upgrade: " + before.record + " -> " + after.record);
        Map<String, Object> expected = fields(before.game), actual = fields(after.game);
        for (String field : expected.keySet()) {
            if (!Objects.equals(expected.get(field), actual.get(field)))
                throw new IllegalStateException("Game field changed during upgrade: " + field + ": " + expected.get(field) + " -> " + actual.get(field));
        }
        if (!after.record.equals(readRecord(Path.of(args[3]))))
            throw new IllegalStateException("Candidate's independent record mirror differs from its committed snapshot");
        System.out.println("PASS: all " + expected.size() + " game fields and record preserved; candidate snapshot and record mirror agree.");
    }

    private static SavedState load(Path snapshot, Path record) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(snapshot)) {
            @Override protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                ObjectStreamClass incoming = super.readClassDescriptor();
                // Fail closed if a future model adds fields the verifier does not yet compare.
                if (incoming.getName().startsWith("com.arcxya.doudizhu.")) {
                    Class<?> type = Class.forName(incoming.getName());
                    ObjectStreamClass local = ObjectStreamClass.lookup(type);
                    Map<String, String> expected = schema(local), actual = schema(incoming);
                    if (type == TableSnapshot.class && !actual.containsKey("record")) expected.remove("record");
                    if (!expected.equals(actual) || local.getSerialVersionUID() != incoming.getSerialVersionUID())
                        throw new InvalidClassException(incoming.getName(), "Unsupported snapshot schema; update the semantic verifier");
                }
                return incoming;
            }
        }) {
            input.setObjectInputFilter(info -> {
                if (info.depth() > 64 || info.references() > 10000 || info.streamBytes() > 1048576 || info.arrayLength() > 10000)
                    return ObjectInputFilter.Status.REJECTED;
                Class<?> type = info.serialClass();
                while (type != null && type.isArray()) type = type.getComponentType();
                if (type == null || type.isPrimitive() || type.getName().startsWith("java.lang.") || type.getName().startsWith("java.util.") || type.getName().startsWith("com.arcxya.doudizhu."))
                    return ObjectInputFilter.Status.UNDECIDED;
                return ObjectInputFilter.Status.REJECTED;
            });
            Object value = input.readObject();
            if (input.read() != -1) throw new IOException("Unexpected trailing snapshot data");
            if (value instanceof TableSnapshot table) {
                if (table.format != 1 || table.game == null) throw new IOException("Unsupported table snapshot");
                Counts counts = table.record == null ? readRecord(record) : new Counts(table.record.games, table.record.wins, table.record.score);
                return new SavedState(table.game, counts, table.record != null);
            }
            if (value instanceof SavedTable table && table.game != null)
                return new SavedState(table.game, new Counts(table.games, table.wins, table.score), false);
            throw new IOException("Unrecognized saved table");
        }
    }

    private static Map<String, String> schema(ObjectStreamClass descriptor) {
        Map<String, String> fields = new TreeMap<>();
        for (ObjectStreamField field : descriptor.getFields())
            fields.put(field.getName(), field.isPrimitive() ? String.valueOf(field.getTypeCode()) : field.getTypeString());
        return fields;
    }

    private static Map<String, Object> fields(Object value) throws IllegalAccessException {
        Map<String, Object> result = new TreeMap<>();
        for (var field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object item = field.get(value);
            if (item instanceof int[] numbers) item = Arrays.toString(numbers);
            if (item instanceof Move) item = fields(item);
            result.put(field.getName(), item);
        }
        return result;
    }

    private static Counts readRecord(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        if (!root.getTagName().equals("map")) throw new IOException("Invalid record preferences root");
        Map<String, Integer> values = new HashMap<>();
        var nodes = root.getElementsByTagName("int");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String key = item.getAttribute("name");
            if (Set.of("games", "wins", "score").contains(key) && values.put(key, Integer.valueOf(item.getAttribute("value"))) != null)
                throw new IOException("Duplicate record preference: " + key);
        }
        if (!values.keySet().equals(Set.of("games", "wins", "score"))) throw new IOException("Missing record preferences");
        return new Counts(values.get("games"), values.get("wins"), values.get("score"));
    }
}

// Java serialization wire models, checked against every incoming field and UID above. Keep these
// independent of APK code so the verifier cannot accidentally invoke the implementation under test.
class SavedTable implements Serializable {
    private static final long serialVersionUID = 6861108738685167990L;
    Game game; int games, wins, score;
}
class TableSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;
    int format; Game game; MatchRecord record;
}
class MatchRecord implements Serializable {
    private static final long serialVersionUID = 1L;
    int games, wins, score;
}
class Game implements Serializable {
    private static final long serialVersionUID = -2247460378412267329L;
    List<List<Integer>> hands; List<Integer> bottom; int level, turn;
    String phase; int bidCount, highBid, bidder, landlord;
    Move last; int lastPlayer, passes, multiplier; int[] played; List<String> status;
    int winner; boolean spring, settled; int delta;
}
class Move implements Serializable {
    private static final long serialVersionUID = 1167266074364606188L;
    List<Integer> cards; Kind kind; int key, span;
}
enum Kind {
    SINGLE, PAIR, TRIPLE, TRIPLE_SINGLE, TRIPLE_PAIR, STRAIGHT, PAIRS, PLANE,
    PLANE_SINGLE, PLANE_PAIR, FOUR_SINGLE, FOUR_PAIR, BOMB, ROCKET
}
