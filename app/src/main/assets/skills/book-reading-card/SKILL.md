---
name: Book Reading Card
archetypes: [book, reading, literature, highlights, notes, review, summary]
blocks: [vela-progress-ring, vela-metadata-card, vela-checklist]
confidence_threshold: 0.7
description: Reading progress, book metadata, and key points checklist
---

## Schema
```json
{
  "title": "string",
  "author": "string | null",
  "status": "string | null",
  "progress": "number | null",
  "rating": "number | null",
  "keyPoints": "string[]",
  "tags": "string[]"
}
```

## Extractor
Extract structured book data from this markdown. Return ONLY valid JSON matching the schema.

- title: book title (frontmatter title field, or first heading)
- author: author name if mentioned, otherwise null
- status: reading status if mentioned (e.g. "reading", "finished", "abandoned"), otherwise null
- progress: reading progress as a number 0-100 if mentioned (e.g. "page 150 of 300" → 50), otherwise null
- rating: numeric rating 1-10 or 1-5 if mentioned, otherwise null
- keyPoints: list of key insights, highlights, or important points from the notes
- tags: genre, themes, or topic tags if mentioned, otherwise empty array
