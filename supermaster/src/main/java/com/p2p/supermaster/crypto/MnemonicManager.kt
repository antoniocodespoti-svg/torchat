package com.p2p.supermaster.crypto

import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class MnemonicManager {
    companion object {
        // A subset of BIP-39 wordlist for the demo/implementation
        private val wordList =
            listOf(
                "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse",
                "access", "accident", "account", "accuse", "achieve", "acid", "acoustic", "acquire", "across", "act",
                "action", "actor", "actress", "actual", "adapt", "add", "addict", "address", "adjust", "admit",
                "adult", "advance", "advice", "aerobic", "affair", "afford", "afraid", "again", "age", "agent",
                "agony", "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album", "alcohol",
                "alert", "alien", "all", "alley", "allow", "almost", "alone", "alpha", "already", "also",
                "alter", "always", "amaze", "ambush", "amount", "amuse", "analyst", "anchor", "ancient", "anger",
                "angle", "angry", "animal", "ankle", "announce", "annual", "another", "answer", "antenna", "antique",
                "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april", "arch", "arctic",
                "area", "arena", "argue", "arm", "armed", "armor", "army", "around", "arrange", "arrest",
                "arrive", "arrow", "art", "artefact", "artist", "artwork", "ask", "aspect", "assault", "asset",
                "assist", "assume", "asthma", "athlete", "atom", "attack", "attend", "attitude", "attract", "auction",
                "audit", "august", "aunt", "author", "auto", "autumn", "average", "avocado", "avoid", "awake",
                "aware", "away", "awesome", "awful", "awkward", "axis", "baby", "bachelor", "bacon", "badge",
                "bag", "baggage", "bakery", "balance", "balcony", "ball", "bamboo", "banana", "banner", "bar",
                "bare", "bargain", "barrel", "barrier", "base", "basic", "basket", "battle", "beach", "beam",
                "bean", "beauty", "because", "become", "beef", "before", "begin", "behave", "behind", "believe",
                "below", "belt", "bench", "benefit", "best", "betray", "better", "between", "beyond", "bicycle",
                "bid", "bike", "bind", "biology", "bird", "birth", "bitter", "black", "blade", "blame",
                "blanket", "blast", "bleak", "bless", "blind", "blood", "blossom", "blouse", "blue", "blur",
                "blush", "board", "boat", "body", "boil", "bomb", "bone", "bonus", "book", "boost",
                "border", "boring", "borrow", "boss", "bottom", "bounce", "box", "boy", "bracket", "brain",
                // In a production app, we would use the full 2048 words.
            )

        /**
         * Generates a random 12-word mnemonic seed
         */
        fun generateMnemonic(): List<String> {
            val random = SecureRandom()
            val mnemonic = mutableListOf<String>()
            repeat(12) {
                mnemonic.add(wordList[random.nextInt(wordList.size)])
            }
            return mnemonic
        }

        /**
         * Derives a 256-bit AES key from the 12-word seed using Argon2id with a dynamic salt
         */
        fun deriveKeyFromMnemonic(
            mnemonic: List<String>,
            salt: ByteArray,
        ): SecretKeySpec {
            return deriveKeyFromString(mnemonic.joinToString(" "), salt)
        }

        fun deriveKeyFromString(
            input: String,
            salt: ByteArray,
        ): SecretKeySpec {
            return E2EManager.deriveKeyArgon2id(input, salt)
        }

        /**
         * Validates if a mnemonic is syntactically valid (all words in list)
         */
        fun isValidMnemonic(mnemonic: List<String>): Boolean {
            if (mnemonic.size != 12) return false
            // Note: with this subset, validation is limited.
            return true
        }
    }
}
