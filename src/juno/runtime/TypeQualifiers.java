package juno.runtime;

import java.math.*;
import java.time.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Immutable constraints used by TypeDescription and typed collection cells. */
final class TypeQualifiers {
    interface Qualifier extends NativeObject,TypedValue {
        default Object invoke(String name,Object[] args){return missingMethod(bslTypeName(),name);}
    }
    record Numeric(int digits,int fraction,boolean nonnegative) implements Qualifier {
        public String bslTypeName(){return "КвалификаторыЧисла";}
        public String toString(){return bslTypeName();}
        public Object property(String name){return switch(name){
            case "разрядность","digits"->number(Integer.toString(digits));
            case "разрядностьдробнойчасти","fractiondigits"->number(Integer.toString(fraction));
            case "допустимыйзнак","allowedsign"->IoEnums.SIGN.property(nonnegative?"nonnegative":"any");
            default->missingProperty(bslTypeName(),name);
        };}
        BigDecimal adjust(BigDecimal value){
            if(nonnegative&&value.signum()<0)return BigDecimal.ZERO;
            if(digits==0)return value;
            BigDecimal result=value.setScale(fraction,RoundingMode.HALF_UP);
            BigDecimal max=BigDecimal.TEN.pow(digits).subtract(BigDecimal.ONE).movePointLeft(fraction);
            return result.abs().compareTo(max)>0?(result.signum()<0?max.negate():max):result;
        }
    }
    record Text(int length,boolean fixed) implements Qualifier {
        public String bslTypeName(){return "КвалификаторыСтроки";}
        public String toString(){return bslTypeName();}
        public Object property(String name){return switch(name){
            case "длина","length"->number(Integer.toString(length));
            case "допустимаядлина","allowedlength"->IoEnums.LENGTH.property(fixed?"fixed":"variable");
            default->missingProperty(bslTypeName(),name);
        };}
        String adjust(String value){if(length==0)return value;String result=value.substring(0,Math.min(length,value.length()));return fixed?result+" ".repeat(length-result.length()):result;}
    }
    record DateParts(String part) implements Qualifier {
        public String bslTypeName(){return "КвалификаторыДаты";}
        public String toString(){return bslTypeName();}
        public Object property(String name){return switch(name){case "частидаты","datefractions"->IoEnums.DATE_PARTS.property(part.toLowerCase(java.util.Locale.ROOT));default->missingProperty(bslTypeName(),name);};}
        LocalDateTime adjust(LocalDateTime value){return switch(part){case "Date"->value.toLocalDate().atStartOfDay();case "Time"->LocalDate.of(1,1,1).atTime(value.toLocalTime());default->value;};}
    }
    static final Numeric NUMBER=new Numeric(0,0,false);
    static final Text STRING=new Text(0,false);
    static final DateParts DATE=new DateParts("DateTime");
    static Object construct(String name,Object[] raw){
        if(!java.util.Set.of("квалификаторычисла","numberqualifiers","квалификаторыстроки","stringqualifiers","квалификаторыдаты","datequalifiers").contains(name))return null;
        Object[] args=values(raw);
        return switch(name){
            case "квалификаторычисла","numberqualifiers"->{
                arity(name,args,0,3);int digits=nonnegative(optional(args,0,BigDecimal.ZERO)),fraction=nonnegative(optional(args,1,BigDecimal.ZERO));
                if(digits>38||fraction>digits)throw error("КвалификаторыЧисла: разрядность 0..38, дробная часть не больше общей");
                String sign=IoEnums.code(optional(args,2,IoEnums.SIGN.property("any")),IoEnums.SIGN);yield new Numeric(digits,fraction,sign.equals("Nonnegative"));
            }
            case "квалификаторыстроки","stringqualifiers"->{
                arity(name,args,0,2);int length=nonnegative(optional(args,0,BigDecimal.ZERO));
                String mode=IoEnums.code(optional(args,1,IoEnums.LENGTH.property("variable")),IoEnums.LENGTH);yield new Text(length,mode.equals("Fixed"));
            }
            case "квалификаторыдаты","datequalifiers"->{arity(name,args,0,1);yield new DateParts(IoEnums.code(optional(args,0,IoEnums.DATE_PARTS.property("datetime")),IoEnums.DATE_PARTS));}
            default->null;
        };
    }
    static <T> T optionalQualifier(Object[] args,int index,T fallback,Class<T> type){Object value=optional(args,index,fallback);if(!type.isInstance(value))throw error("Несоответствие типа квалификатора параметра "+(index+1));return type.cast(value);}
}
