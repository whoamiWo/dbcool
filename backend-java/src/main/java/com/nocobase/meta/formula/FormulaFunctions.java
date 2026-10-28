package com.nocobase.meta.formula;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.AviatorBoolean;
import com.googlecode.aviator.runtime.type.AviatorDouble;
import com.googlecode.aviator.runtime.type.AviatorLong;
import com.googlecode.aviator.runtime.type.AviatorNil;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorString;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FormulaEngine 的 Airtable 方言函数集(IF / AND / OR / CONCAT / LEFT / RIGHT / LEN /
 * UPPER / LOWER / TRIM / ROUND / ABS / SUM / AVG / COUNT / MIN / MAX / MAP / FILTER /
 * JOIN / SPLIT / DATE / YEAR / DATEDIFF)。
 *
 * <p>仅注册纯函数,不开启 Java 反射调用(安全约束 C-R05)。
 * 注册幂等:重复注册(如多次类加载)静默忽略。
 */
final class FormulaFunctions {

    private FormulaFunctions() {
    }

    static void registerAll() {
        for (AbstractFunction f : new AbstractFunction[]{
                new IfFunc(), new AndFunc(), new OrFunc(),
                new ConcatFunc(), new LeftFunc(), new RightFunc(), new LenFunc(),
                new UpperFunc(), new LowerFunc(), new TrimFunc(),
                new RoundFunc(), new AbsFunc(),
                new SumFunc(), new AvgFunc(), new CountFunc(), new MinFunc(), new MaxFunc(),
                new MapFunc(), new FilterFunc(), new JoinFunc(), new SplitFunc(),
                new DateFunc(), new YearFunc(), new DateDiffFunc()}) {
            try {
                AviatorEvaluator.addFunction(f);
            } catch (Exception ignored) {
                // 函数已注册,幂等
            }
        }
    }

    private static boolean isTrue(AviatorObject arg, Map<String, Object> env) {
        Object v = arg.getValue(env);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static String str(AviatorObject arg, Map<String, Object> env) {
        Object v = arg.getValue(env);
        return v == null ? null : String.valueOf(v);
    }

    private static double num(AviatorObject arg, Map<String, Object> env) {
        Object v = arg.getValue(env);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** 将入参规整为可迭代集合:null→空,集合/数组→原样,逗号分隔字符串→拆分。 */
    @SuppressWarnings("unchecked")
    private static List<Object> asList(AviatorObject arg, Map<String, Object> env) {
        Object v = arg.getValue(env);
        if (v == null) return Collections.emptyList();
        if (v instanceof Collection<?> c) return new ArrayList<>((Collection<Object>) c);
        if (v instanceof Object[] arr) return Arrays.asList(arr);
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(java.lang.reflect.Array.get(v, i));
            return out;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return Collections.emptyList();
        return Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toList());
    }

    /** 提取集合中的数值(跳过非数值项)。 */
    private static List<Double> numbers(List<Object> list) {
        List<Double> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.doubleValue());
            } else if (o != null) {
                try {
                    out.add(Double.parseDouble(String.valueOf(o).trim()));
                } catch (NumberFormatException ignored) {
                    // 非数值项跳过
                }
            }
        }
        return out;
    }

    /** 宽松解析日期:支持 yyyy-MM-dd / yyyy/MM/dd / ISO datetime / epoch millis。 */
    private static LocalDate parseDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (v instanceof Number n) return java.time.Instant.ofEpochMilli(n.longValue())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        if (s.length() >= 10) s = s.substring(0, 10).replace('/', '-');
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class IfFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "IF";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject c, AviatorObject a, AviatorObject b) {
            return isTrue(c, env) ? a : b;
        }
    }

    private static final class AndFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "AND";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
            return AviatorBoolean.valueOf(isTrue(a, env) && isTrue(b, env));
        }
    }

    private static final class OrFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "OR";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
            return AviatorBoolean.valueOf(isTrue(a, env) || isTrue(b, env));
        }
    }

    private static final class ConcatFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "CONCAT";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
            return new AviatorString(str(a, env) + str(b, env));
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b, AviatorObject c) {
            return new AviatorString(str(a, env) + str(b, env) + str(c, env));
        }
    }

    private static final class LeftFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "LEFT";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject s, AviatorObject n) {
            String str = str(s, env);
            if (str == null) return AviatorNil.NIL;
            int len = (int) num(n, env);
            return new AviatorString(str.substring(0, Math.max(0, Math.min(len, str.length()))));
        }
    }

    private static final class RightFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "RIGHT";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject s, AviatorObject n) {
            String str = str(s, env);
            if (str == null) return AviatorNil.NIL;
            int len = (int) num(n, env);
            return new AviatorString(len >= str.length() ? str : str.substring(str.length() - len));
        }
    }

    private static final class LenFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "LEN";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject s) {
            String str = str(s, env);
            return str == null ? AviatorNil.NIL : AviatorLong.valueOf(str.length());
        }
    }

    private static final class UpperFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "UPPER";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject s) {
            String str = str(s, env);
            return str == null ? AviatorNil.NIL : new AviatorString(str.toUpperCase());
        }
    }

    private static final class LowerFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "LOWER";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject s) {
            String str = str(s, env);
            return str == null ? AviatorNil.NIL : new AviatorString(str.toLowerCase());
        }
    }

    private static final class TrimFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "TRIM";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject s) {
            String str = str(s, env);
            return str == null ? AviatorNil.NIL : new AviatorString(str.trim());
        }
    }

    private static final class RoundFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "ROUND";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject n) {
            return AviatorLong.valueOf(Math.round(num(n, env)));
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject n, AviatorObject digits) {
            int d = (int) num(digits, env);
            return AviatorDouble.valueOf(
                    BigDecimal.valueOf(num(n, env)).setScale(d, RoundingMode.HALF_UP).doubleValue());
        }
    }

    private static final class AbsFunc extends AbstractFunction {
        @Override
        public String getName() {
            return "ABS";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject n) {
            return AviatorDouble.valueOf(Math.abs(num(n, env)));
        }
    }

    // ================= 聚合函数 =================

    private static final class SumFunc extends AbstractFunction {
        @Override public String getName() { return "SUM"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1) {
            List<Double> nums = numbers(asList(a1, env));
            if (nums.isEmpty()) return AviatorNil.NIL;
            double sum = nums.stream().mapToDouble(Double::doubleValue).sum();
            return (sum == Math.floor(sum) && !Double.isInfinite(sum)) ? AviatorLong.valueOf((long) sum) : AviatorDouble.valueOf(sum);
        }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1, AviatorObject a2) {
            List<Double> nums = numbers(asList(a1, env));
            if (nums.isEmpty()) return AviatorNil.NIL;
            nums.add(num(a2, env));
            double sum = nums.stream().mapToDouble(Double::doubleValue).sum();
            return (sum == Math.floor(sum) && !Double.isInfinite(sum)) ? AviatorLong.valueOf((long) sum) : AviatorDouble.valueOf(sum);
        }
    }

    private static final class AvgFunc extends AbstractFunction {
        @Override public String getName() { return "AVG"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1) {
            List<Double> nums = numbers(asList(a1, env));
            if (nums.isEmpty()) return AviatorNil.NIL;
            double avg = nums.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            return (avg == Math.floor(avg) && !Double.isInfinite(avg)) ? AviatorLong.valueOf((long) avg) : AviatorDouble.valueOf(avg);
        }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1, AviatorObject a2) {
            List<Double> nums = numbers(asList(a1, env));
            if (nums.isEmpty()) return AviatorNil.NIL;
            nums.add(num(a2, env));
            double avg = nums.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            return (avg == Math.floor(avg) && !Double.isInfinite(avg)) ? AviatorLong.valueOf((long) avg) : AviatorDouble.valueOf(avg);
        }
    }

    private static final class CountFunc extends AbstractFunction {
        @Override public String getName() { return "COUNT"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1) {
            List<Object> list = asList(a1, env);
            return AviatorLong.valueOf(list.stream().filter(Objects::nonNull).count());
        }
    }

    private static final class MinFunc extends AbstractFunction {
        @Override public String getName() { return "MIN"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1) {
            List<Double> nums = numbers(asList(a1, env));
            if (nums.isEmpty()) return AviatorNil.NIL;
            double min = nums.stream().min(Double::compare).orElse(Double.NaN);
            return Double.isNaN(min) ? AviatorNil.NIL : ((min == Math.floor(min) && !Double.isInfinite(min)) ? AviatorLong.valueOf((long) min) : AviatorDouble.valueOf(min));
        }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1, AviatorObject a2) {
            double x = num(a1, env), y = num(a2, env);
            return AviatorDouble.valueOf(Math.min(x, y));
        }
    }

    private static final class MaxFunc extends AbstractFunction {
        @Override public String getName() { return "MAX"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1) {
            List<Double> nums = numbers(asList(a1, env));
            if (nums.isEmpty()) return AviatorNil.NIL;
            double max = nums.stream().max(Double::compare).orElse(Double.NaN);
            return Double.isNaN(max) ? AviatorNil.NIL : ((max == Math.floor(max) && !Double.isInfinite(max)) ? AviatorLong.valueOf((long) max) : AviatorDouble.valueOf(max));
        }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject a1, AviatorObject a2) {
            double x = num(a1, env), y = num(a2, env);
            return AviatorDouble.valueOf(Math.max(x, y));
        }
    }

    // ================= 数组函数 =================

    private static final class MapFunc extends AbstractFunction {
        @Override public String getName() { return "MAP"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject iterable, AviatorObject transformExpr) {
            List<Object> list = asList(iterable, env);
            String expr = str(transformExpr, env);
            if (list.isEmpty() || expr == null) return new AviatorString("");
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(",");
                Object val = list.get(i);
                Map<String, Object> localEnv = new HashMap<>(env);
                localEnv.put("$", val);
                Object result = evalSimple(expr, localEnv);
                out.append(formatValue(result));
            }
            out.append("]");
            return new AviatorString(out.toString());
        }
    }

    private static final class FilterFunc extends AbstractFunction {
        @Override public String getName() { return "FILTER"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject iterable, AviatorObject predicateExpr) {
            List<Object> list = asList(iterable, env);
            String pred = str(predicateExpr, env);
            if (list.isEmpty() || pred == null) return new AviatorString("[]");
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object val : list) {
                Map<String, Object> localEnv = new HashMap<>(env);
                localEnv.put("$", val);
                Object res = evalSimple(pred, localEnv);
                if (res instanceof Boolean b && b) {
                    if (!first) out.append(",");
                    out.append(formatValue(val));
                    first = false;
                }
            }
            out.append("]");
            return new AviatorString(out.toString());
        }
    }

    private static final class JoinFunc extends AbstractFunction {
        @Override public String getName() { return "JOIN"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject separator, AviatorObject iterable) {
            String sep = str(separator, env);
            List<Object> list = asList(iterable, env);
            if (list.isEmpty()) return new AviatorString("");
            return new AviatorString(list.stream().map(Object::toString).collect(Collectors.joining(sep != null ? sep : "")));
        }
    }

    private static final class SplitFunc extends AbstractFunction {
        @Override public String getName() { return "SPLIT"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject strArg, AviatorObject delimiter) {
            String s = str(strArg, env);
            String delim = str(delimiter, env);
            if (s == null) return new AviatorString("[]");
            if (delim == null || delim.isEmpty()) return new AviatorString("[" + formatValue(s) + "]");
            String[] parts = s.split(escRegex(delim), -1);
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) out.append(",");
                out.append("\"").append(escapeJson(parts[i])).append("\"");
            }
            out.append("]");
            return new AviatorString(out.toString());
        }
    }

    // ================= 日期函数 =================

    private static final class DateFunc extends AbstractFunction {
        @Override public String getName() { return "DATE"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject year, AviatorObject month, AviatorObject day) {
            int y = (int) num(year, env);
            int m = (int) num(month, env);
            int d = (int) num(day, env);
            try {
                LocalDate dt = LocalDate.of(y, m, d);
                return new AviatorString(dt.format(DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (Exception e) {
                return AviatorNil.NIL;
            }
        }
    }

    private static final class YearFunc extends AbstractFunction {
        @Override public String getName() { return "YEAR"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject dateArg) {
            LocalDate dt = parseDate(dateArg.getValue(env));
            return dt == null ? AviatorNil.NIL : AviatorLong.valueOf(dt.getYear());
        }
    }

    private static final class DateDiffFunc extends AbstractFunction {
        @Override public String getName() { return "DATEDIFF"; }
        @Override public AviatorObject call(Map<String, Object> env, AviatorObject start, AviatorObject end) {
            LocalDate d1 = parseDate(start.getValue(env));
            LocalDate d2 = parseDate(end.getValue(env));
            if (d1 == null || d2 == null) return AviatorNil.NIL;
            return AviatorLong.valueOf(Period.between(d1, d2).getDays());
        }
    }

    /** 把字符串转义为正则字面量 */
    private static String escRegex(String s) {
        if (s == null) return "";
        return s.replaceAll("([\\\\^$.|?*+(){}])", "\\\\$1");
    }

    // ================= 工具方法 =================

    private static Object evalSimple(String expr, Map<String, Object> env) {
        try {
            return AviatorEvaluator.execute(expr, env);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (v instanceof Number n) return String.valueOf(n);
        if (v instanceof Boolean b) return String.valueOf(b);
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
