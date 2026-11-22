# Plugin messages

## Active menu style

Post `PluginMessage("right-click-styler", "style-request")` to request the current working style. Subscribe to `PluginMessage` events with namespace `right-click-styler` and name `style`.

Style messages are posted on the client thread at startup, after a request, and when the working theme changes. A reply to `style-request` is always queued to run later on the client thread, even when the request is posted from it, so it never arrives during the posting call. A `style` message can arrive at any time, not only as a reply; never post `style-request` in response to one, as that creates a feedback loop. The data map is immutable and uses standard Java types:

| Field | Type | Meaning |
| --- | --- | --- |
| `version` | Integer | `1` |
| `active` | Boolean | `true` |
| `font` | java.awt.Font | Effective menu font, including size |
| `text` | java.awt.Color | Default text colour |
| `background` | java.awt.Color | Background with effective opacity |
| `border` | java.awt.Color | Border colour |
| `radius` | Integer | Rounded corner arc size |
| `showBorder` | Boolean | Border enabled |
| `showShadow` | Boolean | Drop shadow enabled |
| `textShadow` | Boolean | Text shadow enabled |

Shutdown sends a `style` message containing only `version=1` and `active=false`. Consumers should discard the shared style and use their own appearance. Consumers starting later should request a snapshot; no reply means no active compatible provider. This exchange does not copy or modify saved settings.
