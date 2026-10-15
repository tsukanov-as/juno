package juno.runtime;

import java.text.*;
import java.util.Locale;

/**
 * Order of strings for the comparison operators, Мин/Макс and the sorting of collections.
 * It is the dictionary order of 1C 8.3.27.1606: lower case before upper case, ё after е, Latin before
 * Cyrillic, digits before letters, and the space and punctuation characters are ordinary characters
 * with a weight below the digits (they are ignorable in the JDK rules). Equality stays exact.
 *
 * RULES is the JDK 21 Russian collation with the blank and punctuation section re-ordered after the
 * order observed on the platform (2601 pairs of letters and digits, 12769 pairs of single characters,
 * no difference); it is a literal so that the order does not depend on the JDK that runs juno.
 */
final class StringOrder {
    private StringOrder() {}
    private static final String RULES =
        "='\u200b'=\u200c=\u200d=\u200e=\u200f=\u0000 =\u0001 =\u0002 =\u0003 =\u0004=\u0005 =\u0006 =\u0007 =" +
        "\u0008 ='\t'='\u000b' =\u000e=\u000f ='\u0010' =\u0011 =\u0012 =\u0013=\u0014 =\u0015 =\u0016 =\u0017 =" +
        "\u0018=\u0019 =\u001a =\u001b =\u001c =\u001d=\u001e =\u001f =\u007f=\u0080 =\u0081 =\u0082 =\u0083 =" +
        "\u0084 =\u0085=\u0086 =\u0087 =\u0088 =\u0089 =\u008a =\u008b=\u008c =\u008d =\u008e =\u008f =\u0090 =" +
        "\u0091=\u0092 =\u0093 =\u0094 =\u0095 =\u0096 =\u0097=\u0098 =\u0099 =\u009a =\u009b =\u009c =\u009d=" +
        "\u009e =\u009f='\ufeff';\u0301;\u0300;\u0306;\u0302;\u030c;\u030a;\u030d;\u0308;\u030b;\u0303;\u0307;" +
        "\u0304;\u0337;\u0327;\u0328;\u0323;\u0332;\u0305;\u0309;\u030e;\u030f;\u0310;\u0311;\u0312;\u0313;" +
        "\u0314;\u0315;\u0316;\u0317;\u0318;\u0319;\u031a;\u031b;\u031c;\u031d;\u031e;\u031f;\u0320;\u0321;" +
        "\u0322;\u0324;\u0325;\u0326;\u0329;\u032a;\u032b;\u032c;\u032d;\u032e;\u032f;\u0330;\u0331;\u0333;" +
        "\u0334;\u0335;\u0336;\u0338;\u0339;\u033a;\u033b;\u033c;\u033d;\u033e;\u033f;\u0342;\u0344;\u0345;" +
        "\u0360;\u0361;\u0483;\u0484;\u0485;\u0486;\u20d0;\u20d1;\u20d2;\u20d3;\u20d4;\u20d5;\u20d6;\u20d7;" +
        "\u20d8;\u20d9;\u20da;\u20db;\u20dc;\u20dd;\u20de;\u20df;\u20e0;\u20e1<'\t'<'\n'<'\u000b'<'\f'<'\r'<" +
        "' '<'\u00a0'<'\u2000'<'\u2001'<'\u2002'<'\u2003'<'\u2004'<'\u2005'<'\u2006'<'\u2007'<'\u2008'<'\u2009'<" +
        "'\u200a'<'\u3000'<'_'<'-';\u00ad;\u2010;\u2011;\u2012;\u2015;\u2212<'\u2013'<'\u2014'<','<';'<':'<" +
        "'!'<'?'<'.'<'''<'\"'<'«'<'»'<'('<')'<'['<']'<'{'<'}'<'@'<'*'<'/'<'\\'<'&'<'#'<'%'<'`'<'^'<'§'<'©'<" +
        "'®'<'°'<'+'<'±'<'÷'<'×'<'<'<'='<'>'<'|'<'~'<'$'<'£'<'€'<'¯'<'¡'<'¿'<'´'<'¨'<'·'<'¸'<'¶'<'¤'<'฿'<" +
        "'¢'<'₡'<'₢'<'₫'<'₣'<'₤'<'₥'<'₦'<'₧'<'₨'<'₪'<'₩'<'¥'<'¬'<'¦'<'µ'<0<1<2<3<4<5<6<7<8<9<¼<½<¾<a,A<b," +
        "B<c,C<d,D<ð,Ð<e,E<f,F<g,G<h,H<i,I<j,J<k,K<l,L<m,M<n,N<o,O<p,P<q,Q<r,R<s, S & SS,ß<t,T& TH, Þ &TH," +
        " þ <u,U<v,V<w,W<x,X<y,Y<z,Z&AE,Æ&AE,æ&OE,Œ&OE,œ& 9 < ҂ & Z < а , А< б , Б< в , В< г , Г; ґ , Ґ; ҕ ," +
        " Ҕ; ѓ , Ѓ; ғ , Ғ< д , Д< ђ , Ђ< е , Е; ҽ , Ҽ; ё , Ё; ҿ , Ҿ< є , Є< ж , Ж; җ , Җ; ӂ , Ӂ< з , З; ҙ ," +
        " Ҙ< ѕ , Ѕ< и , И< і , І; Ӏ < ї , Ї< й , Й< ј , Ј< к , К; ҟ , Ҟ; ӄ , Ӄ; ҝ , Ҝ; ҡ , Ҡ; ќ , Ќ; қ , Қ<" +
        " л , Л< љ , Љ< м , М< н , Н; ѣ ; ң , Ң; ҥ , Ҥ; һ , Һ; ӈ , Ӈ< њ , Њ< о , О; ҩ , Ҩ< п , П; ҧ , Ҧ< р ," +
        " Р< с , С; ҫ , Ҫ< т , Т; ҭ , Ҭ< ћ , Ћ< у , У; ү , Ү< ў , Ў< ұ , Ұ< ф , Ф< х , Х; ҳ , Ҳ< ц , Ц; ҵ ," +
        " Ҵ< ч , Ч; ҷ ; Ҷ; ҹ , Ҹ; ӌ , Ӌ< џ , Џ< ш , Ш< щ , Щ< ъ , Ъ< ы , Ы< ь , Ь< э , Э< ю , Ю< я , Я< ѡ ," +
        " Ѡ< Ѣ < ѥ , Ѥ< ѧ , Ѧ< ѩ , Ѩ< ѫ , Ѫ< ѭ , Ѭ< ѯ , Ѯ< ѱ , Ѱ< ѳ , Ѳ< ѵ , Ѵ; ѷ , Ѷ< ѹ , Ѹ< ѻ , Ѻ< ѽ , Ѽ<" +
        " ѿ , Ѿ< ҁ , Ҁ&NO,'№'";
    private static final ThreadLocal<RuleBasedCollator> TERTIARY = ThreadLocal.withInitial(StringOrder::create);

    private static RuleBasedCollator create() {
        try {
            RuleBasedCollator collator = new RuleBasedCollator(RULES);
            collator.setStrength(Collator.TERTIARY);
            collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
            return collator;
        } catch (ParseException e) {
            throw new IllegalStateException("Invalid string order rules", e);
        }
    }

    /** Total order that agrees with equality: strings the rules cannot tell apart fall back to UTF-16 order. */
    static int compare(String x, String y) {
        if (x.equals(y)) return 0;
        int result = TERTIARY.get().compare(x, y);
        return result != 0 ? result : x.compareTo(y);
    }

    /** Case is not significant (СтрСравнить, sorting of tables); ss and ß, е and ё stay different, as in 1C. */
    static int compareIgnoringCase(String x, String y) {
        return x.equals(y) ? 0 : TERTIARY.get().compare(x.toLowerCase(Locale.ROOT), y.toLowerCase(Locale.ROOT));
    }
}
