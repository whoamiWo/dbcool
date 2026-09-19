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
import java.util.Map;

/**
 * FormulaEngine 的 Airtable 方言函数集(IF / AND / OR / CONCAT / LEFT / RIGHT / LEN /
 * UPPER / LOWER / TRIM / ROUND / ABS)。
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
                new RoundFunc(), new AbsFunc()}) {
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
}
