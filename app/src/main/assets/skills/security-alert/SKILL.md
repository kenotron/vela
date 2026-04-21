---
name: Security Alert Tracker
archetypes: [security, oauth, alert, incident, breach, vulnerability, warning, phishing]
blocks: [vela-action-list, vela-metadata-card, vela-status-badge]
confidence_threshold: 0.72
description: Track status and response actions for security events
---

## Schema
```json
{
  "title": "string",
  "app": "string | null",
  "scope": "string | null",
  "date": "string | null",
  "severity": "string | null",
  "status": "string",
  "actions": "string[]"
}
```

## Extractor
Extract structured security alert data from this markdown. Return ONLY valid JSON matching the schema.

- title: alert title or type (first heading or frontmatter title)
- app: the application or service involved, or null
- scope: what the alert affects (e.g. "read:user email"), or null
- date: when the alert occurred, or null
- severity: critical / high / medium / low / info if mentioned, otherwise null
- status: current status (e.g. "open", "resolved", "investigating") — default "open"
- actions: list of recommended or required response actions as imperatives
