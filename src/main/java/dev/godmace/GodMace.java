  private static boolean callOrbital(String method, ServerLevel level, int... args) {
        try {
            Class<?> logic = Class.forName("com.mcclip.orbitalstrike.OrbitalStrikeLogic");
            if ("summonNuke".equals(method)) {
                logic.getMethod(method, ServerLevel.class, int.class, int.class, int.class, int.class)
                    .invoke(null, level, args[0], args[1], args[2], args[3]);
            } else {
                logic.getMethod(method, ServerLevel.class, int.class, int.class)
                    .invoke(null, level, args[0], args[1], args[2]);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("[GodMace] OrbitalStrike+ integration failed: " + e);
            return false;
        }
    }
