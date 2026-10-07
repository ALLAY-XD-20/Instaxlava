package dev.pawan.instaxlava;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Token-based matching used to accept/reject mirror search results for Instagram audio. */
final class IGMirror {

    private static final Set<String> IGNORED = Set.of(
            "official", "audio", "video", "lyrics", "lyric", "prod", "version", "music");

    private IGMirror() {}

    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[|()\\[\\]{}]", " ")
                .replaceAll("\\b(?:feat|ft)\\b\\.?", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static List<String> tokens(String value) {
        return Arrays.stream(normalize(value).split(" "))
                .filter(t -> t.length() > 1 && !IGNORED.contains(t))
                .toList();
    }

    static boolean unknownAuthor(String author) {
        return author == null || author.isBlank()
                || author.equalsIgnoreCase("Unknown") || author.equalsIgnoreCase("User Unknown");
    }

    /** >=50% of title tokens must appear in the candidate, plus at least one author token (when known). */
    static boolean acceptable(String title, String author, String candTitle, String candAuthor) {
        String text = normalize((candTitle == null ? "" : candTitle) + " " + (candAuthor == null ? "" : candAuthor));
        List<String> titleTokens = tokens(title);
        List<String> authorTokens = unknownAuthor(author) ? List.of() : tokens(author);

        if (!titleTokens.isEmpty()) {
            long hit = titleTokens.stream().filter(text::contains).count();
            long need = Math.max(1, (long) Math.ceil(titleTokens.size() * 0.5));
            if (hit < need) return false;
        }
        if (authorTokens.isEmpty()) return true;
        return authorTokens.stream().anyMatch(text::contains);
    }
}
