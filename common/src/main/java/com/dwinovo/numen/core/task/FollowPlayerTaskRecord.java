package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

import java.util.UUID;

/** Typed descriptor for continuously following one online human player. */
public final class FollowPlayerTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "follow_player";

    public final UUID targetUuid;
    public final String targetName;
    public final double distance;

    public FollowPlayerTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            UUID targetUuid,
            String targetName,
            double distance) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.distance = distance;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + targetName;
    }
}
