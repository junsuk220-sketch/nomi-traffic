package nomi.android.traffic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.traffic.R

class RouteCardAdapter(
    private val onCardClick: (RouteCard) -> Unit,
) : RecyclerView.Adapter<RouteCardAdapter.Holder>() {
    private var items: List<RouteCard> = emptyList()
    private var nowMillis: Long = 0L

    fun submit(next: List<RouteCard>, now: Long) {
        items = next
        nowMillis = now
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_card, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.destination.text = item.destinationName
        holder.provider.text = RouteCardCopy.providerLabel(item.provider)
        holder.detail.text = RouteCardCopy.modeLabel(item.mode)
        holder.whenUsed.text = RouteCardCopy.lastUsedLabel(item.lastUsedAtMillis, nowMillis)
        when (item.provider) {
            NavigationEventSource.NAVER -> {
                holder.provider.setBackgroundResource(R.drawable.bg_chip_naver)
                holder.provider.setTextColor(ContextCompat.getColor(ctx, R.color.chip_naver_fg))
            }
            NavigationEventSource.GOOGLE -> {
                holder.provider.setBackgroundResource(R.drawable.bg_chip_google)
                holder.provider.setTextColor(ContextCompat.getColor(ctx, R.color.chip_google_fg))
            }
        }
        holder.itemView.setOnClickListener { onCardClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val destination: TextView = view.findViewById(R.id.routeCardDestination)
        val provider: TextView = view.findViewById(R.id.routeCardProvider)
        val detail: TextView = view.findViewById(R.id.routeCardDetail)
        val whenUsed: TextView = view.findViewById(R.id.routeCardWhen)
    }
}
