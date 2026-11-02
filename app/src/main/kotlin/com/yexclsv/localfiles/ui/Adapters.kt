package com.yexclsv.localfiles.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yexclsv.localfiles.R
import com.yexclsv.localfiles.data.PlaylistRow
import com.yexclsv.localfiles.data.TrackRow
import com.yexclsv.localfiles.databinding.ItemPlaylistBinding
import com.yexclsv.localfiles.databinding.ItemTrackBinding
import kotlinx.coroutines.CoroutineScope

class TrackAdapter(
    private val scope: CoroutineScope,
    private val accent: () -> Int,
    private val onClick: (TrackRow, Int) -> Unit,
    private val onMore: (TrackRow) -> Unit
) : ListAdapter<TrackRow, TrackAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<TrackRow>() {
        override fun areItemsTheSame(a: TrackRow, b: TrackRow) = a.track.key == b.track.key
        override fun areContentsTheSame(a: TrackRow, b: TrackRow) = a == b
    }

    class VH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.art.roundCorners(14f.dp)
        b.artScrim.roundCorners(14f.dp)
        b.more.pressBounce(0.85f)
        return VH(b)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = getItem(position)
        val b = h.b
        val a = accent()
        b.trackTitle.text = r.track.title
        b.trackTitle.setTextColor(if (r.isCurrent) a else Color.parseColor("#F4F4F8"))
        b.trackSub.text = "${r.track.artist} • ${r.track.album}"
        b.duration.text = fmtTime(r.track.durationMs)
        b.art.loadArt(scope, r.track, 168)
        b.artScrim.visibility = if (r.isCurrent) View.VISIBLE else View.GONE
        b.eq.visibility = if (r.isCurrent) View.VISIBLE else View.GONE
        b.eq.color = Color.WHITE
        b.eq.active = r.isPlaying
        b.fav.visibility = if (r.isFav) View.VISIBLE else View.GONE
        b.fav.imageTintList = tintList(a)
        b.root.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(getItem(p), p)
        }
        b.root.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                it.tick()
                onMore(getItem(p))
            }
            true
        }
        b.more.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onMore(getItem(p))
        }
    }
}

class PlaylistAdapter(
    private val scope: CoroutineScope,
    private val accent: () -> Int,
    private val onClick: (PlaylistRow) -> Unit,
    private val onLong: (PlaylistRow) -> Unit
) : ListAdapter<PlaylistRow, PlaylistAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<PlaylistRow>() {
        override fun areItemsTheSame(a: PlaylistRow, b: PlaylistRow) = a.id == b.id
        override fun areContentsTheSame(a: PlaylistRow, b: PlaylistRow) = a == b
    }

    class VH(val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.cover.roundCorners(24f.dp)
        b.root.pressBounce(0.95f)
        return VH(b)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = getItem(position)
        val b = h.b
        b.plName.text = r.name
        b.plCount.text = if (r.count == 1) "1 song" else "${r.count} songs"
        if (r.cover != null) {
            b.cover.loadArt(scope, r.cover, 400)
        } else {
            b.cover.setImageDrawable(com.yexclsv.localfiles.data.ArtPlaceholder.make(r.id))
        }
        if (r.isFav) {
            b.cover.colorFilter = null
            b.badge.visibility = View.VISIBLE
            b.badge.setImageResource(R.drawable.ic_heart)
            b.badge.imageTintList = tintList(accent())
        } else if (r.cover == null) {
            b.badge.visibility = View.VISIBLE
            b.badge.setImageResource(R.drawable.ic_queue_music)
            b.badge.imageTintList = tintList(Color.WHITE)
        } else {
            b.badge.visibility = View.GONE
        }
        b.root.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(getItem(p))
        }
        b.root.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                it.tick()
                onLong(getItem(p))
            }
            true
        }
    }
}
