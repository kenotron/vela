---
name: Recipe Cook-along
archetypes: [recipe, cooking, meal, ingredients, food]
blocks: [vela-step-through, vela-checklist, vela-timer]
confidence_threshold: 0.7
description: Step-by-step cook-along with ingredient tracking and timers
---

## Schema
```json
{
  "title": "string",
  "servings": "string | null",
  "prepTime": "string | null",
  "ingredients": "string[]",
  "steps": "string[]"
}
```

## Extractor
Extract structured recipe data from this markdown. Return ONLY valid JSON matching the schema.

- title: the recipe name (first heading or frontmatter title)
- servings: number of servings if mentioned, otherwise null
- prepTime: total prep + cook time if mentioned, otherwise null
- ingredients: flat list of every ingredient with its quantity (e.g. "200g pasta")
- steps: ordered list of cooking steps as complete sentences. Omit section headings.
