package com.piggyback.hrvmon;

/** PIDs do modo 01 (SAE J1979) que o app sabe decodificar. */
final class Pids {
    /** Bytes de dados de cada PID; 0 = o app não decodifica. */
    static final int[] LEN = new int[256];
    private static final String[] NAME = new String[256];

    // Escala informada pela ECU no PID 0x4F; sem ele valem os padrões (λ até 2, MAP até 255 kPa).
    static volatile float lambdaMax = 2f;
    static volatile float mapMax = 255f;

    static void resetScale() {
        lambdaMax = 2f;
        mapMax = 255f;
    }

    static {
        def(0x04, 1, "Carga calculada");
        def(0x05, 1, "Temperatura do motor");
        def(0x06, 1, "Trim curto banco 1");
        def(0x07, 1, "Trim longo banco 1");
        def(0x0B, 1, "Pressão do coletor (MAP)");
        def(0x0C, 2, "Rotação");
        def(0x0D, 1, "Velocidade");
        def(0x0E, 1, "Avanço de ignição");
        def(0x0F, 1, "Temperatura do ar de admissão");
        def(0x10, 2, "Fluxo de ar (MAF)");
        def(0x11, 1, "Posição da borboleta");
        def(0x23, 2, "Pressão do rail (relativa)");
        def(0x24, 4, "Sonda 1 banda larga: λ + tensão");
        def(0x33, 1, "Pressão barométrica");
        def(0x34, 4, "Sonda 1 banda larga: λ + corrente");
        def(0x44, 2, "λ comandado");
        def(0x49, 1, "Pedal do acelerador");
        def(0x4F, 4, "Escala máxima (λ, sonda, MAP)");
        def(0x52, 1, "Teor de etanol");
        def(0x59, 2, "Pressão do rail (absoluta)");
        def(0x66, 5, "Fluxo de ar (MAF, sensores)");
        def(0x67, 3, "Temperatura do motor (sensores)");
        def(0x68, 7, "Temperatura do ar de admissão (sensores)");
        def(0x70, 10, "Controle de pressão do turbo");
        def(0x72, 5, "Controle da wastegate");
        def(0x87, 5, "Pressão do coletor (faixa ampla)");

        // Só nomes, para o relatório de PIDs suportados.
        NAME[0x01] = "Status dos monitores";
        NAME[0x03] = "Status do sistema de combustível";
        NAME[0x13] = "Sondas presentes";
        NAME[0x15] = "Sonda 2 (pós-catalisador)";
        NAME[0x1C] = "Norma OBD";
        NAME[0x1F] = "Tempo desde a partida";
        NAME[0x21] = "Distância com luz de injeção acesa";
        NAME[0x2E] = "Purga do cânister comandada";
        NAME[0x2F] = "Nível de combustível";
        NAME[0x30] = "Aquecimentos desde limpeza de códigos";
        NAME[0x31] = "Distância desde limpeza de códigos";
        NAME[0x3C] = "Temperatura do catalisador";
        NAME[0x41] = "Status dos monitores neste ciclo";
        NAME[0x42] = "Tensão do módulo";
        NAME[0x43] = "Carga absoluta";
        NAME[0x45] = "Borboleta relativa";
        NAME[0x46] = "Temperatura ambiente";
        NAME[0x47] = "Borboleta B";
        NAME[0x4A] = "Pedal do acelerador E";
        NAME[0x4C] = "Atuador da borboleta comandado";
        NAME[0x51] = "Tipo de combustível";
        NAME[0x55] = "Trim secundário curto (sonda 2)";
        NAME[0x56] = "Trim secundário longo (sonda 2)";
        NAME[0x6C] = "Borboleta comandada/relativa";
        NAME[0x5C] = "Temperatura do óleo";
        NAME[0x5E] = "Consumo de combustível";
        NAME[0x6F] = "Pressão de entrada do turbo";
        NAME[0x73] = "Pressão do escapamento";
        NAME[0x75] = "Rotação/temperatura do turbo A";
    }

    private static void def(int pid, int len, String name) {
        LEN[pid] = len;
        NAME[pid] = name;
    }

    static String name(int pid) {
        return NAME[pid] != null ? NAME[pid] : "—";
    }

    /** Decodifica os dados de {@code pid} a partir de {@code b[o]} e grava no vetor de estado. */
    static void decode(int pid, int[] b, int o, float[] s) {
        int a = b[o];
        int ab = LEN[pid] >= 2 ? a * 256 + b[o + 1] : a;
        switch (pid) {
            case 0x04: s[Ch.LOAD] = a * 100f / 255f; break;
            case 0x05: s[Ch.ECT] = a - 40; break;
            case 0x06: s[Ch.STFT] = a / 1.28f - 100f; break;
            case 0x07: s[Ch.LTFT] = a / 1.28f - 100f; break;
            case 0x0B: s[Ch.MAP_0B] = a * mapMax / 255f; break;
            case 0x0C: s[Ch.RPM] = ab / 4f; break;
            case 0x0D: s[Ch.SPEED] = a; break;
            case 0x0E: s[Ch.TIMING] = a / 2f - 64f; break;
            case 0x0F: s[Ch.IAT] = a - 40; break;
            case 0x10: s[Ch.MAF] = ab / 100f; break;
            case 0x11: s[Ch.THROTTLE] = a * 100f / 255f; break;
            case 0x23: // 10 kPa por unidade -> bar
            case 0x59: s[Ch.RAIL] = ab / 10f; break;
            case 0x24:
            case 0x34: s[Ch.LAMBDA] = ab * lambdaMax / 65536f; break;
            case 0x33: s[Ch.BARO] = a; break;
            case 0x44: s[Ch.LAMBDA_CMD] = ab * 2f / 65536f; break;
            case 0x49: s[Ch.PEDAL] = a * 100f / 255f; break;
            case 0x4F:
                // A = λ máximo; D = MAP máximo em 10 kPa. Zero = usar o padrão.
                if (a > 0) lambdaMax = a;
                if (b[o + 3] > 0) mapMax = b[o + 3] * 10f;
                break;
            case 0x52: s[Ch.ETHANOL] = a * 100f / 255f; break;
            case 0x66:
                // A = bits de suporte; B-C sensor A (1/32 g/s)
                if ((a & 0x01) != 0) s[Ch.MAF] = (b[o + 1] * 256 + b[o + 2]) / 32f;
                break;
            case 0x67:
                // A = bits de suporte; B sensor 1; C sensor 2
                if ((a & 0x01) != 0) s[Ch.ECT] = b[o + 1] - 40;
                else if ((a & 0x02) != 0) s[Ch.ECT] = b[o + 2] - 40;
                break;
            case 0x68:
                // A = bits de suporte; B banco 1 sensor 1; C banco 1 sensor 2
                if ((a & 0x01) != 0) s[Ch.IAT] = b[o + 1] - 40;
                if ((a & 0x02) != 0) s[Ch.IAT2] = b[o + 2] - 40;
                break;
            case 0x70:
                // A = bits de suporte; B-C alvo A; D-E sensor A (1/32 kPa)
                if ((a & 0x01) != 0) s[Ch.BOOST_CMD_ABS] = (b[o + 1] * 256 + b[o + 2]) / 32f;
                if ((a & 0x02) != 0) s[Ch.MAP_70] = (b[o + 3] * 256 + b[o + 4]) / 32f;
                break;
            case 0x72:
                // A = bits de suporte; B comandada A; C posição real A (100/255 %)
                if ((a & 0x01) != 0) s[Ch.WG_CMD] = b[o + 1] * 100f / 255f;
                if ((a & 0x02) != 0) s[Ch.WG] = b[o + 2] * 100f / 255f;
                break;
            case 0x87:
                // A = bits de suporte; B-C sensor A (1/32 kPa)
                if ((a & 0x01) != 0) s[Ch.MAP_87] = (b[o + 1] * 256 + b[o + 2]) / 32f;
                break;
            default:
                break;
        }
    }

    private Pids() {}
}
