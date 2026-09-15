package cn.rfoid.service;
import cn.rfoid.api.ApiException;

import java.nio.file.*;
import java.util.*;

/** Validate the model boundary before any result is persisted or served. */
public final class DetectionValidator {
    private DetectionValidator() {}
    public static List<String> categories(List<String> categories) {
        if (categories == null || categories.isEmpty() || categories.size() > 5 || categories.stream().anyMatch(Objects::isNull)
            || !Set.of("person", "vehicle", "motorcycle", "animal", "obstacle").containsAll(categories)
            || new HashSet<>(categories).size() != categories.size())
            throw ApiException.invalid("请至少选择一种有效的识别目标，且不可重复");
        return List.copyOf(categories);
    }
    public static double number(Object value, double min, double max) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<min || n.doubleValue()>max)
            throw ApiException.invalid("检测参数或模型数值超出有效范围");
        return n.doubleValue();
    }
    public static double sampleSeconds(double value) {
        if (value == 0) return 0; // Explicit all-frames mode, kept in the existing persisted parameter.
        return number(value, .5, 5);
    }
    public static void roi(List<List<Double>> points) {
        if (points.isEmpty()) return;
        if (points.size()!=4) throw ApiException.invalid("危险区必须按顺序标定四个顶点");
        double sign=0,area=0;
        for (int i=0;i<4;i++) {
            List<Double> p=points.get(i),q=points.get((i+1)%4),r=points.get((i+2)%4);
            if (p.size()!=2 || q.size()!=2 || r.size()!=2) throw ApiException.invalid("危险区坐标格式错误");
            number(p.get(0),0,1);number(p.get(1),0,1);
            double cross=(q.get(0)-p.get(0))*(r.get(1)-q.get(1))-(q.get(1)-p.get(1))*(r.get(0)-q.get(0));
            if (Math.abs(cross)<0.00001 || (sign!=0 && Math.signum(cross)!=sign)) throw ApiException.invalid("请沿同一方向标定不交叉的凸四边形");
            sign=Math.signum(cross);area+=p.get(0)*q.get(1)-q.get(0)*p.get(1);
        }
        if (Math.abs(area)/2<0.005) throw ApiException.invalid("危险区面积过小，请重新标定");
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> result(Map<String,Object> result, Path runDir) {
        if (!(result.get("detections") instanceof List<?> list) || list.size()>200) throw new IllegalArgumentException("Invalid detection list");
        if (!(result.get("region") instanceof List<?> region)) throw new IllegalArgumentException("Missing track region");
        roi(region.stream().map(p->((List<?>)p).stream().map(v->number(v,0,1)).toList()).toList());
        if (region.size()!=4) throw new IllegalArgumentException("Missing track region");
        double frames=number(result.get("sampledFrames"),1,14400);
        if (frames != Math.rint(frames)) throw new IllegalArgumentException("Invalid frame count");
        number(result.get("inferenceMs"),0,3_600_000);
        Set<String> keys=new HashSet<>();
        for (Object item:list) {
            if (!(item instanceof Map<?,?> d)) throw new IllegalArgumentException("Invalid detection");
            if (!Set.of("person","vehicle","motorcycle","animal","obstacle").contains(d.get("category"))) throw new IllegalArgumentException("Invalid category");
            if (!Set.of("HIGH","MEDIUM","INFO").contains(d.get("risk")) || !(d.get("inDanger") instanceof Boolean)) throw new IllegalArgumentException("Invalid risk");
            if (Boolean.TRUE.equals(d.get("inDanger")) == "INFO".equals(d.get("risk"))) throw new IllegalArgumentException("Inconsistent risk");
            number(d.get("confidence"),0,1);number(d.get("frameTime"),0,120);
            if (!(d.get("box") instanceof List<?> box) || box.size()!=4) throw new IllegalArgumentException("Invalid box");
            for (Object n:box) number(n,0,1);
            if (((Number)box.get(0)).doubleValue()>=((Number)box.get(2)).doubleValue() || ((Number)box.get(1)).doubleValue()>=((Number)box.get(3)).doubleValue()) throw new IllegalArgumentException("Empty box");
            String key=Objects.toString(d.get("trackKey"),"");
            if (!key.matches("[a-z0-9-]{1,40}") || !keys.add(key)) throw new IllegalArgumentException("Invalid track key");
            for (String field:List.of("label","advice")) {
                if (!(d.get(field) instanceof String s) || s.isBlank() || s.length()>(field.equals("label")?60:500)) throw new IllegalArgumentException("Invalid text");
            }
            checkAsset(Objects.toString(d.get("snapshot"),""),runDir);
        }
        checkAsset(Objects.toString(result.get("preview"),""),runDir);
        return (List<Map<String,Object>>)(List<?>)list;
    }
    public static void checkAsset(String name, Path runDir) {
        if (!name.matches("frame-[0-9]{6}\\.jpg") || !Files.isRegularFile(runDir.resolve(name))) throw new IllegalArgumentException("Invalid snapshot");
    }
}
