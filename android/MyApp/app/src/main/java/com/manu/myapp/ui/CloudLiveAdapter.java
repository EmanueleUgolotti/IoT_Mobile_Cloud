package com.manu.myapp.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.manu.myapp.CloudLiveItem;
import com.manu.myapp.R;

import java.util.List;

/**
 * Mostra le righe della finestra "live" sul Cloud - classificazioni
 * riuscite e tentativi falliti mescolati insieme, distinti solo dal
 * colore del bordo (coerente con i colori di stato gia' usati altrove
 * nell'app: successo/errore).
 */
public class CloudLiveAdapter extends RecyclerView.Adapter<CloudLiveAdapter.ViewHolder> {

    private final List<CloudLiveItem> items;

    public CloudLiveAdapter(List<CloudLiveItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cloud_live_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CloudLiveItem item = items.get(position);

        holder.textTitle.setText(item.title);
        holder.textSubtitle.setText(item.subtitle);
        holder.textTimestamp.setText(item.receivedAt);

        int borderRes = (item.type == CloudLiveItem.TYPE_FAILURE)
                ? R.drawable.bg_result_card_error
                : R.drawable.bg_result_card;
        holder.card.setBackgroundResource(borderRes);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View card;
        TextView textTitle;
        TextView textSubtitle;
        TextView textTimestamp;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardCloudLiveRow);
            textTitle = itemView.findViewById(R.id.textCloudLiveTitle);
            textSubtitle = itemView.findViewById(R.id.textCloudLiveSubtitle);
            textTimestamp = itemView.findViewById(R.id.textCloudLiveTimestamp);
        }
    }
}