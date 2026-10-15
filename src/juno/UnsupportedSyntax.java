package juno;

import java.util.*;
import java.util.regex.*;

/** Early migration diagnostic without mistaking strings/comments for loader calls. */
final class UnsupportedSyntax {
    static void check(String source, String file) {
        StringBuilder code = new StringBuilder(source);
        boolean quoted = false, comment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') { comment = false; continue; }
            if (comment) { code.setCharAt(i, ' '); continue; }
            if (c == '"') {
                code.setCharAt(i, ' ');
                if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    code.setCharAt(++i, ' ');
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (quoted) { code.setCharAt(i, ' '); continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                code.setCharAt(i, ' ');
                comment = true;
            }
        }
        Pattern pattern = Pattern.compile("(?imu)^\\h*#\\h*(?:Использовать|Use)(?![\\p{L}\\p{N}_])"
            + "|(?<![\\p{L}\\p{N}_.])(?:ПодключитьСценарий|AttachScript)\\h*\\(");
        Matcher found = pattern.matcher(code);
        Set<String> local = new HashSet<>();
        Matcher declaration = Pattern.compile("(?iu)(?:Процедура|Функция|Procedure|Function)\\s+(ПодключитьСценарий|AttachScript)\\s*\\(").matcher(code);
        while (declaration.find()) local.add(declaration.group(1).toLowerCase(Locale.ROOT));
        while (found.find()) {
            String token = found.group().stripLeading();
            if (!token.startsWith("#")) {
                String name = token.substring(0, token.indexOf('(')).strip().toLowerCase(Locale.ROOT);
                if (local.contains(name)) continue;
                int previous = found.start() - 1;
                while (previous >= 0 && Character.isWhitespace(code.charAt(previous))) previous--;
                if (previous >= 0 && code.charAt(previous) == '.') continue;
            }
            int offset = found.start(), line = 1, column = 1;
            for (int i = 0; i < offset; i++) {
                if (source.charAt(i) == '\n') { line++; column = 1; }
                else column++;
            }
            throw new Juno.Failure(List.of(new Juno.Diagnostic(file, "compile", "juno", "UNSUPPORTED_OS_LOADER",
                "OneScript dynamic loader is not supported; declare local dependencies/namespaces in juno.json and use qualified constructors",
                line, column, line, column, offset, offset)), null);
        }
    }
}
