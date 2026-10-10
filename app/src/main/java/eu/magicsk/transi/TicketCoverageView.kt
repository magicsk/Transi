package eu.magicsk.transi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import eu.magicsk.transi.data.models.IdsBkLinks
import eu.magicsk.transi.data.models.SeasonPass
import eu.magicsk.transi.data.models.TicketCatalogue
import eu.magicsk.transi.data.models.TicketCoverage
import eu.magicsk.transi.databinding.ViewTicketCoverageBinding

/**
 * Whether "My pass" covers a journey, else the single ticket it needs, with a button to IDS BK (iOS TripTicketButton).
 * Hidden for walk-only journeys.
 */
class TicketCoverageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val binding = ViewTicketCoverageBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
        binding.ticketButton.setOnClickListener { openIdsBk(context) }
        ViewCompat.replaceAccessibilityAction(
            binding.ticketButton, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.ticket_open_ids_bk_action), null,
        )
    }

    fun bind(coverage: TicketCoverage) {
        isVisible = coverage != TicketCoverage.NoTransit
        binding.ticketStatus.isVisible = false
        binding.ticketCaption.isVisible = false
        binding.ticketButton.contentDescription = null
        when (coverage) {
            is TicketCoverage.Covered -> {
                status(R.string.ticket_covered, R.drawable.ic_verified, R.color.table_green,
                    context.getString(R.string.ticket_covered_until, SeasonPass.formatDay(coverage.lastDay)))
                binding.ticketButton.setText(R.string.ticket_open_ids_bk)
            }
            is TicketCoverage.Expired -> {
                val day = SeasonPass.formatDay(coverage.lastDay)
                val title = if (coverage.ended) R.string.ticket_pass_expired else R.string.ticket_pass_ends
                status(context.getString(title, day), R.drawable.ic_warning, R.color.table_orange)
                buy(coverage.ticket)
            }
            is TicketCoverage.PartlyCovered -> {
                status(zoneList(R.plurals.ticket_pass_covers, coverage.passZones), R.drawable.ic_verified, R.color.table_green)
                buy(coverage.ticket, coverage.zones)
            }
            is TicketCoverage.NotCovered -> {
                coverage.reason?.let {
                    status(R.string.ticket_not_covered, R.drawable.ic_warning, R.color.table_orange, mismatchText(it))
                }
                buy(coverage.ticket)
            }
            TicketCoverage.Unknown -> binding.ticketButton.setText(R.string.ticket_buy_in_ids_bk)
            TicketCoverage.NoTransit -> Unit
        }
    }

    private fun status(@StringRes title: Int, @DrawableRes icon: Int, @ColorRes color: Int, detail: String? = null) =
        status(context.getString(title), icon, color, detail)

    private fun status(title: String, @DrawableRes icon: Int, @ColorRes color: Int, detail: String? = null) {
        binding.ticketStatus.isVisible = true
        binding.ticketStatusIcon.setImageResource(icon)
        binding.ticketStatusIcon.imageTintList = ContextCompat.getColorStateList(context, color)
        binding.ticketStatusTitle.text = title
        binding.ticketStatusDetail.text = detail
        binding.ticketStatusDetail.isVisible = detail != null
    }

    /** The ticket for the whole trip, or for the [uncovered] zones only. */
    private fun buy(ticket: TicketCatalogue.Ticket, uncovered: List<String>? = null) {
        val action = uncovered?.let { zoneList(R.plurals.ticket_buy_for, it) } ?: context.getString(R.string.ticket_buy)
        val zones = ticket.zonesCount?.let { resources.getQuantityString(R.plurals.ticket_zones, it, it) }
            ?: context.getString(R.string.ticket_all_zones)
        val price = ticket.formattedPrice()
        binding.ticketButton.text = context.getString(R.string.ticket_buy_label, action, ticket.timeDuration, zones, price)
        binding.ticketButton.contentDescription = context.getString(
            R.string.ticket_buy_description,
            action, resources.getQuantityString(R.plurals.ticket_minutes, ticket.timeDuration, ticket.timeDuration), zones, price,
        )
        binding.ticketCaption.isVisible = true
    }

    private fun mismatchText(reason: SeasonPass.Mismatch): String = when (reason) {
        SeasonPass.Mismatch.BankCard -> context.getString(R.string.ticket_mismatch_bank_card)
        is SeasonPass.Mismatch.Zones -> zoneList(R.plurals.ticket_mismatch_zones, reason.zones)
        is SeasonPass.Mismatch.RegionalBus -> zoneList(R.plurals.ticket_mismatch_regional_bus, reason.zones)
    }

    /** [plural] for [zones]: "zone 111" or "zones 100, 101". */
    private fun zoneList(@PluralsRes plural: Int, zones: List<String>) =
        resources.getQuantityString(plural, zones.size, zones.joinToString(", "))
}

/** Opens the IDS BK app, else its Play Store page: in the store app, then on the web. */
fun openIdsBk(context: Context) {
    val intents = listOfNotNull(context.packageManager.getLaunchIntentForPackage(IdsBkLinks.PACKAGE)) +
        IdsBkLinks.storeUrls.map { Intent(Intent.ACTION_VIEW, it.toUri()) }
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
}
