---
name: Meeting Action Tracker
archetypes: [meeting, standup, notes, 1on1, interview, discussion, sync]
blocks: [vela-action-list, vela-metadata-card, vela-timeline]
confidence_threshold: 0.7
description: Extracts tasks and decisions from meeting notes with owner tracking
---

## Schema
```json
{
  "title": "string",
  "date": "string | null",
  "attendees": "string[]",
  "decisions": "string[]",
  "actionItems": [{"text": "string", "assignee": "string | null", "due": "string | null"}]
}
```

## Extractor
Extract structured meeting data from this markdown. Return ONLY valid JSON matching the schema.

- title: meeting name or topic (first heading or frontmatter title)
- date: meeting date if mentioned, otherwise null
- attendees: list of people mentioned as attendees/participants
- decisions: list of decisions made, each as a complete sentence
- actionItems: list of tasks/actions. For each, extract text (what to do), assignee (who, or null), due (deadline, or null)
