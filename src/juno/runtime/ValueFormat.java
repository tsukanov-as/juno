package juno.runtime;

import java.math.*;
import java.time.LocalDateTime;
import java.time.format.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;

/** Explicit Format parameters; unsupported options fail instead of becoming no-ops. */
final class ValueFormat {
    private ValueFormat() {}
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final Map<String, String> PARAMETERS = Map.ofEntries(
        Map.entry("чдц", "digits"), Map.entry("nfd", "digits"),
        Map.entry("чрд", "decimal"), Map.entry("nds", "decimal"),
        Map.entry("чрг", "groupSeparator"), Map.entry("ngs", "groupSeparator"),
        Map.entry("чг", "groups"), Map.entry("ng", "groups"),
        Map.entry("дф", "date"), Map.entry("df", "date"),
        Map.entry("л", "locale"), Map.entry("l", "locale"),
        Map.entry("чн", "zero"), Map.entry("nz", "zero"),
        Map.entry("чц", "precision"), Map.entry("nl", "precision"),
        Map.entry("чвн", "leading"), Map.entry("lz", "leading"),
        Map.entry("чо", "negative"), Map.entry("nn", "negative"),
        Map.entry("чс", "shift"), Map.entry("ns", "shift"),
        Map.entry("чф", "numberFormat"), Map.entry("nf", "numberFormat"),
        Map.entry("дп", "emptyDate"), Map.entry("de", "emptyDate"),
        Map.entry("длф", "localDate"), Map.entry("dlf", "localDate"),
        Map.entry("бл", "false"), Map.entry("bf", "false"),
        Map.entry("би", "true"), Map.entry("bt", "true"));
    static Object format(Object value, Object specification) {
        Map<String, String> options = parse(string(specification));
        if (value instanceof BigDecimal n) return number(n, options);
        if (value instanceof Boolean b) return options.getOrDefault(b ? "true" : "false", b ? "Да" : "Нет");
        if (value instanceof LocalDateTime d) {
            BslDates.checked(d);
            if (d.equals(BslDates.EMPTY)) return options.getOrDefault("emptyDate", "");
            Locale locale=locale(options);
            String pattern = options.get("date");
            if(pattern!=null&&!pattern.isEmpty())return date(d,pattern,locale);
            String local=options.get("localDate");
            return local==null||local.isEmpty()?d.format(DateTimeFormatter.ofPattern("dd.MM.uuuu H:mm:ss",locale)):localDate(d,local,locale);
        }
        if (value instanceof String || value == UNDEFINED || value == Special.NULL) return string(value);
        throw new BslException("Формат: неподдерживаемый тип значения");
    }
    private static Map<String, String> parse(String source) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        while (i < source.length()) {
            while (i < source.length() && (Character.isWhitespace(source.charAt(i)) || source.charAt(i) == ';')) i++;
            if (i == source.length()) break;
            int start = i;
            while (i < source.length() && source.charAt(i) != '=' && source.charAt(i) != ';') i++;
            if (i == source.length() || source.charAt(i) != '=') {
                String flag=PARAMETERS.get(source.substring(start,i).strip().toLowerCase(Locale.ROOT));
                if(flag!=null)result.put(flag, "");
                continue;
            }
            String name = source.substring(start, i).strip().toLowerCase(Locale.ROOT);
            String option = PARAMETERS.get(name);
            // Unknown platform parameters are ignored.
            i++;
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
            String value;
            if (i < source.length() && (source.charAt(i) == '\'' || source.charAt(i) == '"')) {
                char quote = source.charAt(i++);
                StringBuilder text = new StringBuilder(); boolean closed = false;
                while (i < source.length()) {
                    char c = source.charAt(i++);
                    if (c != quote) text.append(c);
                    else if (i < source.length() && source.charAt(i) == quote) { text.append(c); i++; }
                    else { closed = true; break; }
                }
                if (!closed) throw error("незакрытые кавычки");
                value = text.toString();
                while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
                if (i < source.length() && source.charAt(i) != ';') throw error("после кавычек ожидался разделитель ;");
            } else {
                start = i;
                while (i < source.length() && source.charAt(i) != ';' && !Character.isWhitespace(source.charAt(i))) i++;
                value = source.substring(start, i);
                // Whitespace inside an unquoted value makes the parameter inapplicable in 1C.
                int rest=i;
                while (i < source.length() && source.charAt(i) != ';') i++;
                if(!source.substring(rest,i).isBlank())option=null;
            }
            if(option!=null)result.put(option, value);
        }
        return result;
    }
    private static BslException error(String detail) { return new BslException("Формат: " + detail); }
    private static int size(String value, String name) {
        try {
            int n = Integer.parseInt(value);
            if (n < 0 || n > 1000) throw error(name + " должен быть в диапазоне 0..1000");
            return n;
        } catch (NumberFormatException e) { return 0; }
    }
    private static Locale locale(Map<String,String> options) {
        String tag=options.getOrDefault("locale", "ru_RU").replace('_','-');
        Locale locale=Locale.forLanguageTag(tag); return locale.getLanguage().isEmpty()?RU:locale;
    }
    private static String number(BigDecimal value, Map<String, String> options) {
        if(value.signum()==0)return options.getOrDefault("zero", "");
        Locale locale=locale(options);
        java.text.DecimalFormatSymbols symbols=java.text.DecimalFormatSymbols.getInstance(locale);
        int shift=0;
        try{shift=Integer.parseInt(options.getOrDefault("shift","0"));}catch(NumberFormatException e){throw error("ЧС: неверное целое");}
        if(Math.abs((long)shift)>1000)throw error("ЧС: диапазон -1000..1000");
        value=value.movePointLeft(shift).stripTrailingZeros();
        int scale=options.containsKey("digits")?size(options.get("digits"),"ЧДЦ"):Math.max(0,value.scale());
        int precision=options.containsKey("precision")?size(options.get("precision"),"ЧЦ"):0;
        if(precision>0) {
            if(!options.containsKey("digits"))scale=Math.min(scale,Math.max(0,precision-Math.max(1,value.precision()-value.scale())));
            else scale=Math.min(scale,precision);
        }
        int[] groups=groups(options.getOrDefault("groups", "3,0"));
        String group=options.getOrDefault("groupSeparator",String.valueOf(symbols.getGroupingSeparator()));
        String decimal=options.getOrDefault("decimal",String.valueOf(symbols.getDecimalSeparator()));
        BigDecimal rounded=value.setScale(scale,RoundingMode.HALF_UP);
        if(rounded.signum()==0&&scale==0&&!options.containsKey("leading"))return options.getOrDefault("zero", "");
        if(precision>0) {
            BigDecimal largest=BigDecimal.TEN.pow(precision).subtract(BigDecimal.ONE).movePointLeft(scale);
            if(rounded.abs().compareTo(largest)>0)rounded=rounded.signum()<0?largest.negate():largest;
        }
        String[] parts=rounded.abs().toPlainString().split("\\.",-1);
        String integer=parts[0];
        if(precision>0&&options.containsKey("leading"))integer="0".repeat(Math.max(0,precision-scale-integer.length()))+integer;
        List<String> chunks=new ArrayList<>();int end=integer.length(),width=groups[0];
        if(width>0&&!group.isEmpty())while(end>width){chunks.add(integer.substring(end-width,end));end-=width;if(groups[1]==0)break;width=groups[1];}
        chunks.add(integer.substring(0,end));Collections.reverse(chunks);
        String result=String.join(group,chunks)+(parts.length>1?decimal+parts[1]:"");
        if(rounded.signum()<0)result=switch(options.getOrDefault("negative","1")){
            case "0"->"("+result+")";case "2"->"- "+result;case "3"->result+"-";case "4"->result+" -";default->"-"+result;
        };
        String pattern=options.get("numberFormat");
        return pattern==null?result:pattern.replace("Ч",result).replace("N",result);
    }
    private static String localDate(LocalDateTime date,String kind,Locale locale) {
        String code=kind.toUpperCase(Locale.ROOT).replace('D','Д').replace('T','В');
        boolean longDate=code.contains("ДД"), time=code.contains("В"), day=code.contains("Д");
        String result="";
        if(day){
            if(longDate)result=date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale));
            else result=date.format(DateTimeFormatter.ofPattern(locale.getLanguage().equals("en")?"M/d/uuuu":"dd.MM.uuuu",locale));
            if(locale.getLanguage().equals("ru"))result=result.replace(" г.","\u00a0г.").replace("\u202fг.","\u00a0г.");
        }
        if(time){String clock=date.format(DateTimeFormatter.ofPattern(locale.getLanguage().equals("en")?"h:mm:ss a":"H:mm:ss",locale));result+=result.isEmpty()?clock:" "+clock;}
        return result.isEmpty()?BslDates.presentation(date):result;
    }
    private static int[] groups(String specification) {
        String[] parts = specification.split(",", -1);
        if (parts.length > 3 || parts.length == 0) throw error("ЧГ: поддерживается n, n,0 или n,m[,0]");
        int first = size(parts[0].strip(), "ЧГ");
        if (parts.length == 1) return new int[]{first, 0};
        int second = size(parts[1].strip(), "ЧГ");
        if (parts.length == 3 && size(parts[2].strip(), "ЧГ") != 0) throw error("ЧГ: третье значение должно быть 0");
        return new int[]{first, second == 0 ? first : second};
    }
    private static String date(LocalDateTime date, String pattern, Locale locale) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pattern.length();) {
            char c = pattern.charAt(i++);
            if (c == '\\') {
                if (i == pattern.length()) throw error("ДФ: незавершённое экранирование");
                result.append(pattern.charAt(i++)); continue;
            }
            if (c == '\'' || c == '"') {
                boolean closed = false;
                while (i < pattern.length()) {
                    char literal = pattern.charAt(i++);
                    if (literal != c) result.append(literal);
                    else if (i < pattern.length() && pattern.charAt(i) == c) { result.append(c); i++; }
                    else { closed = true; break; }
                }
                if (!closed) throw error("ДФ: незакрытые кавычки литерала");
                continue;
            }
            int count = 1;
            while (i < pattern.length() && pattern.charAt(i) == c) { count++; i++; }
            char token = switch (c) { case 'г' -> 'y'; case 'д' -> 'd'; case 'М' -> 'M'; case 'Ч' -> 'H'; case 'ч' -> 'h'; case 'м' -> 'm'; case 'с' -> 's'; case 'в' -> 't'; default -> c; };
            String text = token(date, token, count, locale, pattern.matches(".*[dд].*"));
            if (text == null) {
                if (Character.isLetter(c)) throw error("ДФ: неподдерживаемый символ " + c + "; заключите текст в кавычки");
                text = String.valueOf(c).repeat(count);
            }
            result.append(text);
        }
        return result.toString();
    }
    private static String token(LocalDateTime d, char token, int count, Locale locale, boolean withDay) {
        if (token == 'f') {
            if (count > 9) throw error("ДФ: до 9 знаков долей секунды");
            return pad(d.getNano(), 9).substring(0, count);
        }
        if (token == 't') {
            if (count > 2) throw error("ДФ: t или tt");
            return (d.getHour() < 12 ? "AM" : "PM").substring(0, count);
        }
        if (token == 'M' && (count == 3 || count == 4)) {
            String month=d.format(DateTimeFormatter.ofPattern((withDay?"M":"L").repeat(count),locale));
            return !withDay&&locale.getLanguage().equals("ru")?Character.toUpperCase(month.charAt(0))+month.substring(1):month;
        }
        if (token == 'd' && (count == 3 || count == 4)) return d.format(DateTimeFormatter.ofPattern(count == 3 ? "EEE" : "EEEE", locale));
        int value = switch (token) {
            case 'y' -> count < 3 ? d.getYear() % 100 : d.getYear();
            case 'M' -> d.getMonthValue(); case 'd' -> d.getDayOfMonth(); case 'H' -> d.getHour();
            case 'h' -> d.getHour() % 12 == 0 ? 12 : d.getHour() % 12;
            case 'm' -> d.getMinute(); case 's' -> d.getSecond(); default -> -1;
        };
        if (value < 0) return null;
        if (count > (token == 'y' ? 4 : 2)) throw error("ДФ: неверная длина символа " + token);
        return pad(value, count);
    }
    private static String pad(int value, int width) {
        String text = Integer.toString(value);
        return "0".repeat(Math.max(0, width - text.length())) + text;
    }
}
