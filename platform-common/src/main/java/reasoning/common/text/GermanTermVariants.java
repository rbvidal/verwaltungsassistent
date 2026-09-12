package reasoning.common.text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Expands German terms into their morphological variants for tolerant text
 * matching. German separable-prefix verbs and compounds often appear in the
 * question in a different prefix form than in the corpus text ("Ummeldung" in
 * a question vs. "Anmeldung"/"meldung" in the law text, "gezogen" vs.
 * "umgezogen", "mitbringen" vs. "bringen"). Literal matching fails on these,
 * so the retrieval layer matches a term if the text contains the raw term or
 * any stripped-prefix variant.
 */
public final class GermanTermVariants {

    private static final List<String> PREFIXES = List.of(
            "über", "ueber", "unter", "an", "ab", "um", "aus", "auf", "ein",
            "vor", "nach", "mit", "bei", "ver", "be", "ent", "er", "ge",
            "wider", "zer");

    private GermanTermVariants() {
    }

    /** Raw term + stripped-prefix variants (lowercase, e.g. ummeldung → {ummeldung, meldung}). */
    public static Set<String> of(String term) {
        String t = term.toLowerCase(Locale.GERMANY);
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(t);
        for (String prefix : PREFIXES) {
            if (t.startsWith(prefix) && t.length() > prefix.length() + 3) {
                variants.add(t.substring(prefix.length()));
            }
        }
        return variants;
    }
}
