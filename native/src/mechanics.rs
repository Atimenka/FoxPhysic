//! Damped Hooke springs, with equal/opposite body forces.
use crate::forces::Vec3;

pub fn force_on_b(
    pa: Vec3,
    va: Vec3,
    pb: Vec3,
    vb: Vec3,
    rest: f32,
    stiffness: f32,
    damping: f32,
) -> Vec3 {
    let delta = pa - pb;
    let length = delta.norm();
    if length < 1e-6 {
        return Vec3::zeros();
    }
    let direction = delta / length;
    direction * (stiffness * (length - rest) - damping * (vb - va).dot(&direction))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn relaxed_spring_has_zero_force() {
        assert_eq!(
            force_on_b(
                Vec3::zeros(),
                Vec3::zeros(),
                Vec3::x(),
                Vec3::zeros(),
                1.0,
                100.0,
                10.0
            ),
            Vec3::zeros()
        );
    }
    #[test]
    fn extension_pulls_towards_anchor() {
        let f = force_on_b(
            Vec3::zeros(),
            Vec3::zeros(),
            Vec3::x() * 2.0,
            Vec3::zeros(),
            1.0,
            100.0,
            10.0,
        );
        assert!((f.x + 100.0).abs() < 1e-5);
    }
    #[test]
    fn damping_opposes_separation() {
        let f = force_on_b(
            Vec3::zeros(),
            Vec3::zeros(),
            Vec3::x(),
            Vec3::x(),
            1.0,
            0.0,
            10.0,
        );
        assert!(f.x < 0.0);
    }
    #[test]
    fn coincident_anchors_do_not_produce_nan() {
        assert_eq!(
            force_on_b(
                Vec3::zeros(),
                Vec3::zeros(),
                Vec3::zeros(),
                Vec3::zeros(),
                1.0,
                100.0,
                10.0
            ),
            Vec3::zeros()
        );
    }
}
