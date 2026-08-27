package com.manu.myapp.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.manu.myapp.HistoryItem;
import com.manu.myapp.R;
import com.manu.myapp.ResultDetailActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter per la lista della cronologia. Ogni riga mostra una miniatura
 * dell'immagine, nome file, classe predetta, badge fonte, orario e
 * confidenza. Il tocco su una riga apre il dettaglio completo.
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    private final List<HistoryItem> items;

    public HistoryAdapter(List<HistoryItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HistoryItem item = items.get(position);

        holder.textFileName.setText(item.fileName);
        holder.textLabel.setText(item.label);
        holder.textSourceBadge.setText(item.source);

        int confidencePercent = Math.round(item.confidence * 100);
        holder.textConfidence.setText(confidencePercent + "%");

        String time = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                .format(new Date(item.timestampMillis));
        holder.textTime.setText(time);

        if (item.imagePath != null) {
            // Decodifica a risoluzione ridotta per la miniatura, per non
            // caricare l'immagine intera in memoria per ogni riga della lista
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            Bitmap thumbnail = BitmapFactory.decodeFile(item.imagePath, options);
            holder.imageThumbnail.setImageBitmap(thumbnail);
        }

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), ResultDetailActivity.class);
            intent.putExtra(ResultDetailActivity.EXTRA_FILE_NAME, item.fileName);
            intent.putExtra(ResultDetailActivity.EXTRA_LABEL, item.label);
            intent.putExtra(ResultDetailActivity.EXTRA_CONFIDENCE, item.confidence);
            intent.putExtra(ResultDetailActivity.EXTRA_PROBABILITIES, item.probabilities);
            intent.putExtra(ResultDetailActivity.EXTRA_IMAGE_PATH, item.imagePath);
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageThumbnail;
        TextView textFileName;
        TextView textLabel;
        TextView textSourceBadge;
        TextView textTime;
        TextView textConfidence;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageThumbnail = itemView.findViewById(R.id.imageThumbnail);
            textFileName = itemView.findViewById(R.id.textItemFileName);
            textLabel = itemView.findViewById(R.id.textItemLabel);
            textSourceBadge = itemView.findViewById(R.id.textItemSourceBadge);
            textTime = itemView.findViewById(R.id.textItemTime);
            textConfidence = itemView.findViewById(R.id.textItemConfidence);
        }
    }
}