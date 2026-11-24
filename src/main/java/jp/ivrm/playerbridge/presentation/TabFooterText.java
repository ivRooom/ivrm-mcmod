package jp.ivrm.playerbridge.presentation;

import java.time.Instant;
import jp.ivrm.playerbridge.presentation.PresentationSnapshot.PlayerEntry;
import jp.ivrm.playerbridge.presentation.SidebarLines.LocalInfo;

/**
 * Pure text rendering for the TAB-list footer (#33).
 *
 * <p>The footer is the default board surface: it carries the
 * exact summary the sidebar renders — it delegates to
 * {@link SidebarLines#renderSidebar} so the two surfaces can
 * never drift — joined into one multi-line string for
 * {@code ClientboundTabListPacket}. It is only visible while
 * the player holds TAB, so unlike the always-on sidebar it
 * never occupies the screen.</p>
 *
 * <p>Degradation differs from the sidebar on purpose. When
 * the cached snapshot is not usable (never fetched, or past
 * the stale window) the footer renders as the empty string,
 * which the adapter sends to clear any previously shown
 * footer: stale progress must not linger on a surface whose
 * whole point is a quick, current glance. The sidebar
 * instead degrades to the server basics because it stays on
 * screen. When a snapshot is usable but has no entry for
 * the player yet (just logged in, refetch pending) the
 * footer shows those same server basics, like the sidebar.</p>
 */
public final class TabFooterText {
    private TabFooterText() {
    }

    public static String renderFooter(
            LocalInfo local, PlayerEntry entry, boolean cacheUsable, Instant dailyResetsAt, Instant now) {
        if (!cacheUsable) {
            return "";
        }
        return String.join(
                "\n", SidebarLines.renderSidebar(local, entry, true, dailyResetsAt, now));
    }
}
