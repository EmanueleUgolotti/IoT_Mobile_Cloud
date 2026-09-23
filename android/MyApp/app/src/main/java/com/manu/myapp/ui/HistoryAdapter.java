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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter per la lista della cronologia. Ogni riga mostra una miniatura
 * dell'immagine, nome file, classe predetta, badge fonte, orario e
 * confidenza. Il tocco su una riga apre il dettaglio completo; tenendo
 * premuto si richiede l'eliminazione (con conferma gestita dal Fragment
 * tramite OnDeleteRequestListener).
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    /** Callback invocato quando l'utente chiede di eliminare una voce. */
    public interface OnDeleteRequestListener {
        void onDeleteRequested(HistoryItem item);
    }

    private final List<HistoryItem> items;
    private final OnDeleteRequestListener deleteListener;

    // Decodifica le miniature su thread separati dal principale - farlo
    // in onBindViewHolder in modo sincrono causava un rallentamento
    // visibile (fotogrammi persi) proprio durante l'animazione di
    // passaggio a questa schermata, dato che il thread principale doveva
    // sia animare la transizione sia decodificare piu' immagini insieme.
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);

    public HistoryAdapter(List<HistoryItem> items, OnDeleteRequestListener deleteListener) {
        this.items = items;
        this.deleteListener = deleteListener;
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
            // Svuota subito la miniatura (evita di mostrare per un istante
            // quella riciclata da una riga precedente, mentre la nuova
            // sta ancora caricando).
            holder.imageThumbnail.setImageBitmap(null);

            String pathForThisBind = item.imagePath;
            holder.imageThumbnail.setTag(pathForThisBind);

            thumbnailExecutor.execute(() -> {
                // Decodifica a risoluzione ridotta per la miniatura, per non
                // caricare l'immagine intera in memoria per ogni riga della lista
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                Bitmap thumbnail = BitmapFactory.decodeFile(pathForThisBind, options);

                holder.itemView.post(() -> {
                    // Con lo scroll veloce, questa ViewHolder potrebbe nel
                    // frattempo essere gia' stata riassegnata a un'altra
                    // riga - il controllo sul tag evita di mostrare
                    // l'immagine sbagliata nel posto sbagliato.
                    if (pathForThisBind.equals(holder.imageThumbnail.getTag())) {
                        holder.imageThumbnail.setImageBitmap(thumbnail);
                    }
                });
            });
        } else {
            holder.imageThumbnail.setImageBitmap(null);
        }

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), ResultDetailActivity.class);
            intent.putExtra(ResultDetailActivity.EXTRA_ID, item.id);
            intent.putExtra(ResultDetailActivity.EXTRA_FILE_NAME, item.fileName);
            intent.putExtra(ResultDetailActivity.EXTRA_LABEL, item.label);
            intent.putExtra(ResultDetailActivity.EXTRA_CONFIDENCE, item.confidence);
            intent.putExtra(ResultDetailActivity.EXTRA_PROBABILITIES, item.probabilities);
            intent.putExtra(ResultDetailActivity.EXTRA_IMAGE_PATH, item.imagePath);
            intent.putExtra(ResultDetailActivity.EXTRA_NOTE, item.note);
            v.getContext().startActivity(intent);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onDeleteRequested(item);
            }
            return true;
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