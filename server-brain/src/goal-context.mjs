function nonBlank(value) {
  return typeof value === "string" && value.trim() !== ""
    ? value.trim()
    : undefined;
}

function eventIdentity(event) {
  const id = event?.runId ?? event?.id;
  const type = nonBlank(event?.type) ?? "event";
  return `${type}:${String(id ?? "unknown")}`;
}

export function createGoalContext(event, { kind = "player" } = {}) {
  return Object.freeze({
    goal_id: eventIdentity(event),
    kind,
    source_event_id: event?.id ?? null,
    speaker: nonBlank(event?.playerName) ?? null,
    speaker_uuid: nonBlank(event?.playerUuid) ?? null,
    objective: nonBlank(event?.message) ?? "",
  });
}

export function continueGoalContext(goal, event) {
  if (goal == null) return createGoalContext(event);
  const {
    latest_instruction: _previousInstruction,
    latest_speaker: _previousSpeaker,
    latest_speaker_uuid: _previousSpeakerUuid,
    ...base
  } = goal;
  const latestInstruction =
    nonBlank(event?.message) ?? goal.latest_instruction ?? goal.objective;
  const latestSpeaker = nonBlank(event?.playerName);
  const latestSpeakerUuid = nonBlank(event?.playerUuid);
  const next = {
    ...base,
    latest_event_id: event?.id ?? goal.latest_event_id ?? goal.source_event_id,
  };
  // Avoid paying for the original objective twice in every prompt. A
  // latest_instruction field is useful only when a follow-up actually changed
  // or refined the goal.
  if (latestInstruction !== goal.objective) {
    next.latest_instruction = latestInstruction;
  }
  if (latestSpeaker != null && latestSpeaker !== goal.speaker) {
    next.latest_speaker = latestSpeaker;
  }
  if (
    latestSpeakerUuid != null &&
    latestSpeakerUuid !== goal.speaker_uuid
  ) {
    next.latest_speaker_uuid = latestSpeakerUuid;
  }
  return Object.freeze(next);
}
