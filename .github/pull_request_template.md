The problem in one or two plain sentences, then what changed. Closes #

## Summary

The smallest picture that makes the change clear: pseudocode, a call tree, a short file tree, or a diagram.

## Evidence

- **Before:** the failing test, output or screenshot
- **After:** the passing test, output or screenshot

## Merge danger

**Door:** one-way (costly to roll back: a migration, a wire format, a deleted path) or two-way
**Blast radius:** one phrase

### Design-goal review

One line for each principle (1–8 in CLAUDE.md) and Reactive UI rule the diff touches: how it conforms, or the
justified violation and its issue. Principle 8 always gets a line when the diff touches `:core:state`,
`:core:pipeline`, `:domain` or rule JSON.

### Adversarial review

Reviewer and rounds (`sol-loop` by default), findings fixed, findings declined with a one-line reason each.
