package dev.thathunky.solstice;

/** Crops, animals and fishing by season. {@code roll} is a random number in [0, 1). */
final class HarvestRules {

    enum Crop { NORMAL, BONUS, CANCEL }

    /**
     * Per-season numbers, indexed by season id.
     *
     * @param cropBonus      chance a growth step adds one extra stage
     * @param cropCancel     chance a growth step under open sky is cancelled (greenhouses exempt)
     * @param breedCancel    chance a breeding attempt produces no baby
     * @param babyGrowth     how many times faster newborns grow up (1 = vanilla)
     * @param fishWait       multiplier on the wait for a bite (1 = vanilla)
     */
    record Numbers(double[] cropBonus, double[] cropCancel, double[] breedCancel, double[] babyGrowth, double[] fishWait) {

        static Numbers defaults() {
            return new Numbers(
                    new double[]{0, 0.25, 0.25, 0},
                    new double[]{0.60, 0, 0, 0},
                    new double[]{0.40, 0, 0, 0},
                    new double[]{1, 2, 1, 1},
                    new double[]{1, 1, 1, 0.75});
        }
    }

    private final Numbers n;

    HarvestRules(Numbers numbers) {
        this.n = numbers;
    }

    Crop crop(Season season, boolean openSky, double roll) {
        int i = season.id();
        if (openSky && roll < n.cropCancel()[i]) {
            return Crop.CANCEL;
        }
        return roll < n.cropBonus()[i] ? Crop.BONUS : Crop.NORMAL;
    }

    boolean cancelBreed(Season season, double roll) {
        return roll < n.breedCancel()[season.id()];
    }

    /** A newborn's age (negative = baby) scaled so it grows up {@code babyGrowth} times faster. */
    int babyAge(Season season, int age) {
        double f = n.babyGrowth()[season.id()];
        return age < 0 && f > 0 && f != 1 ? (int) (age / f) : age;
    }

    double fishWait(Season season) {
        return n.fishWait()[season.id()];
    }
}
