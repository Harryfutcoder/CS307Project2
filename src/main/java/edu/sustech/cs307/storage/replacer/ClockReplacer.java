package edu.sustech.cs307.storage.replacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClockReplacer implements PageReplacer{
    private static class FrameState {
        boolean pinned;
        boolean referenced;
    }

    private final int maxSize;
    private final List<Integer> frames;
    private final Map<Integer, FrameState> states;
    private int clockHand;

    public ClockReplacer(int numPages) {
        this.maxSize = numPages;
        this.frames = new ArrayList<>();
        this.states = new HashMap<>();
        this.clockHand = 0;
    }

    @Override
    public int Victim() {
        if (frames.isEmpty() || !hasEvictableFrame()) {
            return -1;
        }
        while (true) {
            if (frames.isEmpty()) {
                return -1;
            }
            if (clockHand >= frames.size()) {
                clockHand = 0;
            }
            int frameId = frames.get(clockHand);
            FrameState state = states.get(frameId);
            if (state.pinned) {
                clockHand = (clockHand + 1) % frames.size();
                continue;
            }
            if (state.referenced) {
                state.referenced = false;
                clockHand = (clockHand + 1) % frames.size();
                continue;
            }
            frames.remove(clockHand);
            states.remove(frameId);
            if (clockHand >= frames.size() && !frames.isEmpty()) {
                clockHand = 0;
            }
            return frameId;
        }
    }

    @Override
    public void Pin(int frameId) {
        FrameState state = states.get(frameId);
        if (state != null) {
            state.pinned = true;
            return;
        }
        if (frames.size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        FrameState newState = new FrameState();
        newState.pinned = true;
        newState.referenced = true;
        states.put(frameId, newState);
        frames.add(frameId);

    }

    @Override
    public void Unpin(int frameId) {
        FrameState state = states.get(frameId);
        if (state == null || !state.pinned) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        state.pinned = false;
        state.referenced = true;

    }

    @Override
    public int size() {
        return frames.size();
    }

    private boolean hasEvictableFrame() {
        for (FrameState state : states.values()) {
            if (!state.pinned) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        frames.clear();
        states.clear();
        clockHand = 0;
    }
}
