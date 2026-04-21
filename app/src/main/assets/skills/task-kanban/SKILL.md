---
name: Task Kanban Board
archetypes: [task, project, todo, sprint, backlog, kanban, roadmap]
blocks: [vela-kanban, vela-progress-ring]
confidence_threshold: 0.7
description: Kanban board with progress ring for task and project notes
---

## Schema
```json
{
  "title": "string",
  "todo": "string[]",
  "inProgress": "string[]",
  "done": "string[]",
  "blocked": "string[]"
}
```

## Extractor
Extract structured task data from this markdown. Return ONLY valid JSON matching the schema.

- title: project or task list name (first heading or frontmatter title)
- todo: tasks not yet started. Look for sections labelled "todo", "backlog", "to do", or the first list if no labels.
- inProgress: tasks currently being worked on. Look for "in progress", "doing", "wip" sections.
- done: completed tasks. Look for "done", "completed", "finished" sections.
- blocked: blocked tasks. Look for "blocked", "waiting", "on hold" sections.
- If no section labels exist, put all tasks into todo.
