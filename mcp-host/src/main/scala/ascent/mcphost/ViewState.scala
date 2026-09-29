package ascent.mcphost

import heddle.mcp.apps.frame.FrameError
import heddle.mcp.apps.host.{ConsentRequest, Ending}

/** What one framed MCP App view is doing, as its `<ascent-mcp-view>` shows it. */
enum ViewState:
  /** Made, and not yet in a document. */
  case Detached

  /** In a document; the relay and the view are coming up. */
  case Starting

  /** The view is served through its relay. */
  case Serving

  /** A call the view asked to make waits on the user. */
  case Asking(request: ConsentRequest)

  /** The page asked the view to go, and the view has the host's `teardownWait` to answer. It is asked nothing more. */
  case Leaving

  /** The mount ended: the view left or was torn down, navigated, or its port closed. Its frame went with it. */
  case Ended(ending: Ending)

  /** The page took the element out while the view was up, and the frame went with it unasked. */
  case Removed

  /** The frame never started. */
  case Failed(error: FrameError)

  /** Whether the view can still ask for a call: it is coming up, served, or already asking. */
  def live: Boolean = this match
    case Starting | Serving | Asking(_) => true
    case _                              => false

  /** Whether the view is done for good. An element frames its view once. */
  def over: Boolean = this match
    case Ended(_) | Removed | Failed(_) => true
    case _                              => false
end ViewState
