package edu.sustech.cs307.index;

import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import java.util.TreeMap;

import org.pmw.tinylog.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.ArrayList;
import java.util.Map;

public class InMemoryOrderedIndex implements Index {

    private final TreeMap<Value, RID> indexMap;
    private final String persistPath;

    public InMemoryOrderedIndex(String persistPath) {
        this.persistPath = persistPath;
        this.indexMap = new TreeMap<>((left, right) -> {
            try {
                return ValueComparer.compare(left, right);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // read from persistPath
        try {
            File file = new File(persistPath);
            if (file.exists()) {
                ObjectMapper objectMapper = new ObjectMapper();
                TypeReference<ArrayList<IndexEntry>> typeRef = new TypeReference<>() {
                };
                ArrayList<IndexEntry> entries = objectMapper.readValue(file, typeRef);
                if (entries != null) {
                    for (IndexEntry entry : entries) {
                        indexMap.put(entry.key, new RID(entry.pageNum, entry.slotNum));
                    }
                }
            }
        } catch (IOException e) {
            Logger.error("Error loading index data: " + e.getMessage());
        }
    }

    private static class IndexEntry {
        public Value key;
        public int pageNum;
        public int slotNum;

        public IndexEntry() {
        }

        public IndexEntry(Value key, RID rid) {
            this.key = key;
            this.pageNum = rid.pageNum;
            this.slotNum = rid.slotNum;
        }
    }

    public void put(Value value, RID rid) {
        indexMap.put(value, rid);
    }

    public void remove(Value value) {
        indexMap.remove(value);
    }

    public void clear() {
        indexMap.clear();
    }

    public void persist() {
        try {
            File file = new File(persistPath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            ObjectMapper objectMapper = new ObjectMapper();
            ArrayList<IndexEntry> entries = new ArrayList<>();
            for (Map.Entry<Value, RID> entry : indexMap.entrySet()) {
                entries.add(new IndexEntry(entry.getKey(), entry.getValue()));
            }
            objectMapper.writeValue(file, entries);
        } catch (IOException e) {
            Logger.error("Error persisting index data: " + e.getMessage());
        }
    }

    public Iterator<Entry<Value, RID>> all() {
        return indexMap.entrySet().iterator();
    }

    @Override
    public RID EqualTo(Value value) {
        // or throw an exception if preferred
        return indexMap.getOrDefault(value, null);
    }

    /**
     * 返回一个迭代器，该迭代器用于遍历所有严格小于给定值的条目。
     * 
     * @param value 要比较的值
     * @return 一个迭代器，按从大到小的顺序遍历所有严格小于给定值的条目
     */
    @Override
    public Iterator<Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        NavigableMap<Value, RID> subMap = indexMap.headMap(value, isEqual);
        return subMap.descendingMap().entrySet().iterator();
    }

    /**
     * 返回一个迭代器，遍历所有严格大于给定值的条目。
     *
     * @param value 要比较的值
     * @return 一个迭代器，包含所有严格大于指定值的条目
     */
    @Override
    public Iterator<Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        NavigableMap<Value, RID> subMap = indexMap.tailMap(value, isEqual);
        return subMap.entrySet().iterator();
    }

    /**
     * 返回指定范围内的条目迭代器。
     * 
     * @param low        范围的下界
     * @param high       范围的上界
     * @param leftEqual  是否包含下界
     * @param rightEqual 是否包含上界
     * @return 指定范围内条目的迭代器
     */
    @Override
    public Iterator<Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        // 获取范围视图（左闭右开）
        NavigableMap<Value, RID> subMap = indexMap.subMap(
                low, leftEqual,
                high, rightEqual);

        return subMap.entrySet().iterator();
    }
}
