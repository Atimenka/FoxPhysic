//! Drops a soft cube on the floor and reports the worst tetrahedron volume ratio (inversion check).
//! Usage: soft_drop <height_m> <substeps> <iterations> <dt_tick> <distance_compliance> <volume_compliance> <ticks>
use foxphys_core::soft::{grid_tets, SoftBody};
fn main() {
    let a: Vec<f64> = std::env::args().skip(1).map(|s| s.parse().unwrap()).collect();
    let (h0, sub, iters, dt, dc, vc, ticks) = (a[0], a[1] as usize, a[2] as usize, a[3], a[4], a[5], a[6] as usize);
    let (pos0, tets) = grid_tets(4, 4, 4, 0.05);
    let pos: Vec<[f64; 3]> = pos0.iter().map(|q| [q[0] - 0.1, q[1] + h0, q[2] - 0.1]).collect();
    let mut b = SoftBody::from_tets(&pos, &tets, 1100.0, dc, vc).unwrap();
    b.iterations = iters;
    b.damping = 0.2;
    b.floor = Some(0.0);
    if let Some(mu) = a.get(7) { b.floor_friction = *mu; }
    if let Some(f) = a.get(8) { b.inelastic_floor = *f > 0.5; }
    let mut worst = f64::MAX;
    let t0 = std::time::Instant::now();
    let mut worst_t = 0;
    let mut vmax: f64 = 0.0;
    for t in 0..ticks {
        b.step(dt, sub);
        let r = b.volume_ratios();
        let m = r.iter().cloned().fold(f64::MAX, f64::min);
        if m < worst { worst = m; worst_t = t; }
        for v in &b.vel { vmax = vmax.max((v[0]*v[0]+v[1]*v[1]+v[2]*v[2]).sqrt()); }
    }
    let ms_per_tick = t0.elapsed().as_secs_f64() * 1e3 / ticks as f64;
    let ymin = b.pos.iter().map(|p| p[1]).fold(f64::MAX, f64::min);
    let ymax = b.pos.iter().map(|p| p[1]).fold(f64::MIN, f64::max);
    let vfin = b.vel.iter().map(|v| (v[0]*v[0]+v[1]*v[1]+v[2]*v[2]).sqrt()).fold(0.0, f64::max);
    let last = b.volume_ratios().iter().cloned().fold(f64::MAX, f64::min);
    println!("ms/tick={ms_per_tick:.3} h={h0} sub={sub} it={iters} dt={dt} dc={dc} vc={vc} | worst_ratio={worst:.3} at_tick={worst_t} final_min_ratio={last:.3} vmax_seen={vmax:.2} ymin={ymin:.3} ymax={ymax:.3} vfinal={vfin:.4}");
}
