package com.instantaxtion.zcs2;

/** Names for places and people. People's names come from their id and the seed, so they never need saving. */
public final class Names {
    private static final String[] PRE = {"Ash", "Brook", "Cedar", "Elm", "Fair", "Glen", "Hart", "Iron", "Kings", "Lake",
            "Mill", "North", "Oak", "Pine", "Red", "Stone", "West", "Wood", "Clear", "Silver", "Spring", "Maple", "Willow",
            "Cold", "Black", "Green", "High", "Mason", "Rose", "Bright", "Clay", "Cotton", "Deer", "Eagle", "Fox", "Hazel",
            "Lin", "Mead", "Port", "Sutton", "Thorn", "Union", "Wal", "Bell", "Bay", "Copper", "Crest"};
    private static final String[] SUF = {"ford", "ton", "field", "ville", "dale", " Falls", "port", "burg", "wood",
            " Springs", " Hill", "haven", "brook", "view", " Crossing", "ridge", "mont", " Creek", "water", "bury"};
    private static final String[] FIRST = {"James", "Maria", "Robert", "Linda", "Michael", "Sarah", "David", "Karen", "Daniel",
            "Nancy", "Jose", "Lisa", "Chris", "Emily", "Kevin", "Jessica", "Brian", "Ashley", "Jason", "Amanda", "Luis",
            "Megan", "Tyrone", "Keisha", "Wei", "Mei", "Raj", "Priya", "Omar", "Fatima", "Andre", "Rosa", "Sam", "Alex",
            "Jordan", "Taylor", "Morgan", "Casey", "Derek", "Tanya", "Marcus", "Grace", "Ethan", "Chloe", "Noah", "Ava",
            "Hector", "Ines", "Ivan", "Nadia", "Pat", "Riley", "Quinn", "Hannah", "Victor", "Yuki", "Kenji", "Aisha"};
    private static final String[] LAST = {"Smith", "Johnson", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez", "Lee",
            "Walker", "Hall", "Allen", "Young", "King", "Wright", "Lopez", "Hill", "Scott", "Green", "Adams", "Baker",
            "Nelson", "Carter", "Mitchell", "Perez", "Roberts", "Turner", "Phillips", "Campbell", "Parker", "Evans",
            "Edwards", "Collins", "Stewart", "Sanchez", "Morris", "Rogers", "Reed", "Cook", "Morgan", "Bell", "Murphy",
            "Bailey", "Rivera", "Cooper", "Kim", "Nguyen", "Patel", "Chen", "Okafor", "Novak", "Kowalski", "Haddad",
            "Brooks", "Price", "Ward", "Torres", "Gray", "Ramirez", "James", "Watson", "Bennett", "Wood", "Ross"};

    public static String place(Rng rng, int kind) {
        String base = PRE[rng.nextInt(PRE.length)] + SUF[rng.nextInt(SUF.length)];
        switch (kind) {
            case Region.S_CITY: return (rng.chance(0.4f) ? "New " : rng.chance(0.3f) ? "Port " : "") + base;
            case Region.S_BASE: return "Fort " + PRE[rng.nextInt(PRE.length)] + (rng.chance(0.5f) ? "e" : "");
            case Region.S_PRISON: return PRE[rng.nextInt(PRE.length)] + " County Prison";
            case Region.S_AIRBASE: return PRE[rng.nextInt(PRE.length)] + " Air Force Base";
            case Region.S_NAVAL: return "Naval Station " + PRE[rng.nextInt(PRE.length)] + SUF[rng.nextInt(SUF.length)];
            case Region.S_AIRPORT: return base + " International Airport";
            case Region.S_CAMP: return base + " Camp";
            case Region.S_HOLDOUT: return base + " Holdout";
            default: return base;
        }
    }

    private static final String[] STATE_SUF = {"ia", "ota", "ana", "ona", "ado", "ington", "land", "ware", "ina", "is"};

    public static String state(Rng rng) {
        String p = PRE[rng.nextInt(PRE.length)];
        return p + STATE_SUF[rng.nextInt(STATE_SUF.length)];
    }

    public static String country(Rng rng) {
        String[] f = {"The Republic of ", "The Union of ", "The Commonwealth of ", "The Federation of "};
        return f[rng.nextInt(f.length)] + state(rng);
    }

    public static String person(long seed, int id) {
        return FIRST[Rng.hashInt(seed, id, 1, FIRST.length)] + " " + LAST[Rng.hashInt(seed, id, 2, LAST.length)];
    }

    public static String surname(long seed, int id) {
        return LAST[Rng.hashInt(seed, id, 2, LAST.length)];
    }

    private static final String[] ALPHA = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
            "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango",
            "Uniform", "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"};

    /** A radio callsign for a squad or vehicle. */
    public static String callsign(int faction, int id) {
        if (faction == Content.COP) return "Unit " + (10 + id % 90) + "-" + (char) ('A' + (id / 90) % 26);
        return ALPHA[id % ALPHA.length] + " " + (1 + id / ALPHA.length);
    }
}
