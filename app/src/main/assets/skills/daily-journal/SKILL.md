---
name: Daily Journal Dashboard
archetypes: [journal, diary, daily, log, reflection, morning-pages]
blocks: [vela-checklist, vela-heatmap-calendar]
confidence_threshold: 0.7
description: Habits checklist and consistency heatmap for daily notes
---

## Schema
```json
{
  "date": "string | null",
  "mood": "string | null",
  "habits": [{"name": "string", "done": "boolean"}],
  "wins": "string[]",
  "reflections": "string[]"
}
```

## Extractor
Extract structured daily journal data from this markdown. Return ONLY valid JSON matching the schema.

- date: the journal date if mentioned, otherwise null
- mood: mood or energy level if mentioned (e.g. "good", "tired", "energised"), otherwise null
- habits: list of habits mentioned. For each: name and whether it's marked as done/complete (true/false)
- wins: list of things that went well, achievements, or gratitude items
- reflections: list of key thoughts, lessons, or intentions for the day
