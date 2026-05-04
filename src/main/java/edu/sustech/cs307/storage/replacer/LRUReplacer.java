package edu.sustech.cs307.storage.replacer;

import java.util.*;

public class LRUReplacer implements PageReplacer {

    private final int maxSize;
    private final Set<Integer> pinnedFrames = new HashSet<>();
    private final Set<Integer> LRUHash = new HashSet<>();
    private final LinkedList<Integer> LRUList = new LinkedList<>();

    public LRUReplacer(int numPages) {
        this.maxSize = numPages;
    }

    public int Victim() {
        if (LRUList.isEmpty()) {
            return -1;
        }
        int victim = LRUList.removeFirst();
        LRUHash.remove(victim);
        return victim;
    }

    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            return;
        }
        if (LRUHash.contains(frameId)) {
            LRUList.remove((Integer) frameId);
            LRUHash.remove(frameId);
        } else if (size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        pinnedFrames.add(frameId);
    }


    public void Unpin(int frameId) {
        if (!pinnedFrames.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        pinnedFrames.remove(frameId);
        if (!LRUHash.contains(frameId)) {
            LRUList.addLast(frameId);
            LRUHash.add(frameId);
        }
    }


    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }

    public void clear() {
        pinnedFrames.clear();
        LRUHash.clear();
        LRUList.clear();
    }
}
