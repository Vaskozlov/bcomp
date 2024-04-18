/*
 * $Id$
 */
package ru.ifmo.cs.bcomp.ui;

import ru.ifmo.cs.bcomp.*;
import ru.ifmo.cs.bcomp.assembler.AsmNg;
import ru.ifmo.cs.bcomp.assembler.Program;
import ru.ifmo.cs.components.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * @author Dmitry Afanasiev <KOT@MATPOCKuH.Ru>
 */
public class CLI {
    private final BasicComp bcomp;
    private final CPU cpu;
    private final IOCtrl[] ioctrls;
    private final ArrayList<Long> writeList = new ArrayList<>();
    private final ConcurrentMap<Integer, LinkedBlockingDeque<Integer>> pendingIO = new ConcurrentHashMap<>();

    private int sleeptime = 1;
    private volatile long savedPointer;
    private volatile boolean printOnStop = true;
    private volatile boolean printRegsTitle = false;
    private volatile boolean printMicroTitle = false;
    private volatile boolean printMemoryAccesses = false;
    private volatile int sleep = 0;

    private final List<Long> monitoredMemoryWrite = new ArrayList<>();
    private final Map<Integer, Thread> monitors = new HashMap<>();

    public CLI(BasicComp bcomp) {
        this.bcomp = bcomp;

        cpu = bcomp.getCPU();
        cpu.addDestination(ControlSignal.STOR, value -> {
            long addr = cpu.getRegValue(Reg.AR);

            if (printMemoryAccesses || monitoredMemoryWrite.contains(addr)) {
                println("STORE: " + Utils.toHex(addr, 11) + " " + Utils.toHex(value, 16));
            }

            // Saving changed mem addr to print later
            if (!writeList.contains(addr)) {
                writeList.add(addr);
            }
        });

        cpu.addDestination(ControlSignal.LOAD, value -> {
            long addr = cpu.getRegValue(Reg.AR);

            if (printMemoryAccesses) {
                println("LOAD: " + Utils.toHex(addr, 11) + " " + Utils.toHex(value, 16));
            }
        });

        cpu.setCPUStartListener(() -> {
            if (!printOnStop) {
                return;
            }

            writeList.clear();
            // Saving IP/MP to print registers later
            savedPointer = cpu.getRegValue(cpu.getClockState() ? Reg.IP : Reg.MP);
            printRegsTitle();
        });

        // Print changed mem
        cpu.setCPUStopListener(() -> {
            sleep = 0;

            if (!printOnStop) {
                return;
            }

            printRegs(writeList.isEmpty() ? ";;" : ";" + getMemory(writeList.remove(0)));

            for (Long wraddr : writeList) {
                println(String.format("%1$46s", ";") + getMemory(wraddr));
            }
        });

        cpu.setTickFinishListener(() -> {
            if (sleep <= 0) {
                return;
            }

            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                /*totally not empty*/
            }
        });

        ioctrls = bcomp.getIOCtrls();

        Thread ioPoller = new Thread(() -> {
            while (true) {
                try {
                    for (Map.Entry<Integer, LinkedBlockingDeque<Integer>> entry : pendingIO.entrySet()) {
                        if (!ioctrls[entry.getKey()].isReady() && !entry.getValue().isEmpty()) {
                            ioctrls[entry.getKey()].setData(entry.getValue().pollFirst());
                            ioctrls[entry.getKey()].setReady();
                        }
                    }

                    Thread.sleep(2);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        ioPoller.start();
    }

    private String getReg(Reg reg) {
        return Utils.toHex(cpu.getRegValue(reg), cpu.getRegWidth(reg));
    }

    private String getMemory(long addr) {
        return Utils.toHex(addr, 11) + ";" + Utils.toHex(cpu.getMemory().getValue(addr), 16);
    }

    private void printMicroMemory(long addr) {
        if (printMicroTitle) {
            println("Адр    МК       Метка           Расшифровка");
            printMicroTitle = false;
        }
        println(MCDecoder.getFormattedMC(cpu, addr));
    }

    private final Reg[] printRegs = new Reg[]{Reg.IP, Reg.CR, Reg.AR, Reg.DR, Reg.SP, Reg.BR, Reg.AC};

    private void printRegsTitle() {
        if (!printRegsTitle) {
            return;
        }

        StringBuilder builder = new StringBuilder();

        builder.append("Адр");

        if (cpu.getClockState()) {
            builder.append(";Знчн");
        } else {
            builder.append(";МК");
        }

        for (Reg reg : printRegs) {
            builder.append(';');
            builder.append(reg.name());
        }

        builder.append(";NZVC");

        if (cpu.getClockState()) {
            builder.append(";Адр;Знчн");
        } else {
            builder.append(";СчМК");
        }

        println(builder.toString());
        printRegsTitle = false;
    }

    private void printRegs(String add) {
        StringBuilder builder = new StringBuilder();

        if (cpu.getClockState()) {
            builder.append(getMemory(savedPointer));
        } else {
            builder.append(Utils.toHex(savedPointer, 8));
            builder.append(';');
            builder.append(Utils.toHex(cpu.getMicroCode().getValue(savedPointer), 40));
        }

        for (Reg reg : printRegs) {
            builder.append(';');
            builder.append(getReg(reg));
        }

        builder.append(';');
        builder.append(Utils.toBinary(cpu.getRegValue(Reg.PS) & 0xF, 4));

        if (cpu.getClockState()) {
            builder.append(add);
        } else {
            builder.append(";");
            builder.append(getReg(Reg.MP));
        }

        println(builder.toString());
    }

    private void printIO(int ioaddr) {
        println("ВУ" + ioaddr + " " + ioctrls[ioaddr]);
    }

    private boolean checkCmd(String cmd, String check) {
        return cmd.equalsIgnoreCase(check.substring(0, Math.min(check.length(), cmd.length())));
    }

    private void checkResult(boolean result) throws Exception {
        if (!result) {
            throw new Exception("операция не выполнена: выполняется программа");
        }
    }

    @SuppressWarnings("WeakerAccess")
    protected void printHelp() {
        println("Доступные команды:\n"
                + "a[ddress]\t- Пультовая операция \"Ввод адреса\"\n"
                + "w[rite]\t\t- Пультовая операция \"Запись\"\n"
                + "r[ead]\t\t- Пультовая операция \"Чтение\"\n"
                + "s[tart]\t\t- Пультовая операция \"Пуск\"\n"
                + "c[continue]\t- Пультовая операция \"Продолжить\"\n"
                + "ru[n]\t\t- Переключение режима Работа/Останов\n"
                + "cl[ock]\t\t- Переключение режима потактового выполнения\n"
                + "ma[ddress]\t- Переход на микрокоманду\n"
                + "mw[rite] value\t- Запись микрокоманды\n"
                + "mr[ead]\t\t- Чтение микрокоманды\n"
                + "md[ecode]\t- Декодировать текущую микрокоманду\n"
                + "mdecodea[ll]\t- Декодировать всю микропрограмму\n"
                + "stat[e]\t\t- Вывести регистр состояния БЭВМ\n"
                + "io\t\t- Вывод состояния всех ВУ\n"
                + "io addr\t\t- Вывод состояния указанного ВУ\n"
                + "io addr value\t- Запись value в указанное ВУ\n"
                + "smartio addr value\t- Запись value в указанное ВУ с ожиданием готовности, после успешной записи устанавливает готовность ВУ\n"
                + "flag addr\t- Установка флага готовности указанного ВУ\n"
                + "asm\t\t\t- Ввод программы на ассемблере\n"
                + "sleep value\t- Задержка между тактами при фоновом выполнении\n"
                + "{exit|quit}\t- Выход из эмулятора\n"
                + "(0000-FFFF)\t- Ввод шестнадцатеричного значения в клавишный регистр\n"
                + "labelname\t- Ввод адреса метки в клавишный регистр\n"
                + "accesses\t- Выводить доступы к памяти\n"
                + "exe\t\t- Выполняет команды по инструкция до останов\n"
                + "load\t\t- Загрузка программы в память\n"
                + "monitor addr\t\t - следит за изменением ВУ\n"
                + "awrite addr\t - выводит изменения в памяти данной ячейки\n"
                + "rfrom addr\t - выводит значение ячейки памяти\n"
                + "wto addr value\t - записывает значение по адресу\n"
        );
    }

    private final Scanner input = new Scanner(System.in);

    public void cli() {
        println("Эмулятор Базовой ЭВМ. Версия v1.45.10 " + CLI.class.getPackage().getImplementationVersion() + "\n"
                + "БЭВМ готова к работе.\n"
                + "Используйте ? или help для получения справки");

        String line;
        for (; ; ) {
            try {
                line = fetchLine();
            } catch (Exception e) {
                break;
            }

            processLine(line);
        }

        Runtime.getRuntime().exit(0);
    }

    private void readAndSetIp() throws Exception {
        print("Введите начальный адрес: ");
        String codeBegin = input.nextLine();
        int codeAddress = Integer.parseInt(codeBegin, 16);
        cpu.getRegister(Reg.IR).setValue(codeAddress);
        checkResult(cpu.executeSetAddr());
    }

    private void loadToMemory() throws Exception {
        readAndSetIp();

        println("Введите текст программы. Для окончания введите END");

        while (true) {
            try {
                String line = input.nextLine();

                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                if (checkCmd(line, "exit")) {
                    return;
                }

                if (checkCmd(line, "end")) {
                    println("Введите адрес исполнения: ");
                    String executionBegin = input.nextLine();
                    int executionAddress = Integer.parseInt(executionBegin, 16);
                    cpu.getRegister(Reg.IR).setValue(executionAddress);
                    checkResult(cpu.executeSetAddr());
                    return;
                }

                if (checkCmd(line, "ip")) {
                    readAndSetIp();
                    continue;
                }

                int code = Integer.parseInt(line, 16);
                cpu.getRegister(Reg.IR).setValue(code);
                checkResult(cpu.executeWrite());
            } catch (Exception e) {
                println("Ошибка, попробуйте еще раз.");
            }
        }
    }

    @SuppressWarnings("WeakerAccess")
    protected void processLine(String line) {
        int i;
        int value;
        String[] cmds = line.strip().split("[ \t]+");

        if (cmds.length == 0) {
            return;
        }

        for (i = 0, printRegsTitle = printMicroTitle = true; i != cmds.length; ++i) {
            String cmd = cmds[i];

            if (cmd.isEmpty()) {
                continue;
            }

            if (cmd.charAt(0) == '#') {
                break;
            }

            if (checkCmd(cmd, "exit") || checkCmd(cmd, "quit")) {
                Runtime.getRuntime().exit(0);
            }
            if (checkCmd(cmd, "?") || checkCmd(cmd, "help")) {
                printHelp();
                continue;
            }

            try {
                if (checkCmd(cmd, "address")) {
                    checkResult(cpu.executeSetAddr());
                    continue;
                }

                if (checkCmd(cmd, "write")) {
                    checkResult(cpu.executeWrite());
                    continue;
                }

                if (checkCmd(cmd, "read")) {
                    checkResult(cpu.executeRead());
                    continue;
                }

                if (checkCmd(cmd, "start")) {
                    if (i == cmds.length - 1) {
                        sleep = sleeptime;
                        checkResult(cpu.startStart());
                    } else {
                        checkResult(cpu.executeStart());
                    }
                    continue;
                }

                if (checkCmd(cmd, "continue")) {
                    if (i == cmds.length - 1) {
                        sleep = sleeptime;
                        checkResult(cpu.startContinue());
                    } else {
                        checkResult(cpu.executeContinue());
                    }
                    continue;
                }

                if (checkCmd(cmd, "clock")) {
                    println("Такт: " + (cpu.invertClockState() ? "Нет" : "Да"));
                    continue;
                }

                if (checkCmd(cmd, "run")) {
                    cpu.invertRunState();
                    println("Режим работы: " + (cpu.getProgramState(State.W) == 1 ? "Работа" : "Останов"));
                    continue;
                }

                if (checkCmd(cmd, "maddress")) {
                    checkResult(cpu.executeSetMP());
                    printMicroMemory(cpu.getRegValue(Reg.MP));
                    continue;
                }

                if (checkCmd(cmd, "mwrite")) {
                    if (i == cmds.length - 1) {
                        throw new Exception("команда mwrite требует аргумент");
                    }

                    long mc = Long.parseLong(cmds[++i], 16);
                    long addr = cpu.getRegValue(Reg.MP);
                    checkResult(cpu.executeMCWrite(mc));
                    printMicroMemory(addr);
                    continue;
                }

                if (checkCmd(cmd, "mread")) {
                    long addr = cpu.getRegValue(Reg.MP);
                    checkResult(cpu.executeMCRead());
                    printMicroMemory(addr);
                    continue;
                }

                if (checkCmd(cmd, "mdecode")) {
                    printMicroMemory(cpu.getRegValue(Reg.MP));
                    continue;
                }

                if (checkCmd(cmd, "mdecodeall")) {
                    for (i = 0; i < (1L << cpu.getMicroCode().getAddrWidth()); i++)
                        if (cpu.getMicroCode().getValue(i) != 0)
                            printMicroMemory(i);
                    continue;
                }

                if (checkCmd(cmd, "state")) {
                    for (State state : State.values())
                        print(state.name() + ": " + cpu.getProgramState(state) + " ");

                    println("");
                    continue;
                }

                if (checkCmd(cmd, "io")) {
                    if (i == cmds.length - 1) {
                        for (int ioaddr = 0; ioaddr < 4; ioaddr++) {
                            printIO(ioaddr);
                        }
                        continue;
                    }

                    int ioaddr = Integer.parseInt(cmds[++i], 16);

                    if (i < cmds.length - 1) {
                        value = Integer.parseInt(cmds[++i], 16);
                        ioctrls[ioaddr].setData(value);
                    }

                    printIO(ioaddr);
                    continue;
                }

                if (checkCmd(cmd, "smartio")) {
                    if (i == cmds.length - 1) {
                        for (int ioaddr = 0; ioaddr < 4; ioaddr++) {
                            printIO(ioaddr);
                        }
                        continue;
                    }

                    int ioaddr = Integer.parseInt(cmds[++i], 16);

                    if (i < cmds.length - 1) {
                        value = Integer.parseInt(cmds[++i], 16);

                        if (!pendingIO.containsKey(ioaddr)) {
                            pendingIO.put(ioaddr, new LinkedBlockingDeque<>());
                        }

                        pendingIO.get(ioaddr).add(value);
                    }

                    printIO(ioaddr);
                    continue;
                }

                if (checkCmd(cmd, "monitor")) {
                    Integer ioaddr = Integer.parseInt(cmds[++i], 16);

                    if (monitors.containsKey(ioaddr)) {
                        monitors.remove(ioaddr);
                        println("Удален мониторинг устройства с номером " + Utils.toHex(ioaddr, 11));
                        continue;
                    }

                    Thread th = new Thread(() -> {
                        while (true) {
                            ioctrls[ioaddr].setReady();

                            if (ioctrls[ioaddr].getData() != 0) {
                                println(String.format("Device %x: %x", ioaddr, ioctrls[ioaddr].getData()));
                                ioctrls[ioaddr].setData(0);
                            }

                            try {
                                Thread.sleep(1);
                            } catch (Exception e) {

                            }
                        }
                    });
                    monitors.put(ioaddr, th);
                    println("Добавлен мониторинг устройства с номером " + Utils.toHex(ioaddr, 11));
                    th.start();
                    continue;
                }

                if (checkCmd(cmd, "flag")) {
                    if (i == cmds.length - 1) {
                        throw new Exception("команда flag требует аргумент");
                    }

                    int ioaddr = Integer.parseInt(cmds[++i], 16);
                    ioctrls[ioaddr].setReady();
                    printIO(ioaddr);
                    continue;
                }

                if (checkCmd(cmd, "awrite")) {
                    if (i == cmds.length - 1) {
                        throw new Exception("команда awrite требует аргумент");
                    }

                    long addr = Integer.parseInt(cmds[++i], 16);
                    monitoredMemoryWrite.add(addr);
                    println("Вывод изменений в памяти по адресу " + Utils.toHex(addr, 11));
                    continue;
                }

                if (checkCmd(cmd, "rfrom")) {
                    if (i == cmds.length - 1) {
                        throw new Exception("команда rfrom требует аргумент");
                    }

                    long addr = Integer.parseInt(cmds[++i], 16);
                    println("Значение ячейки памяти по адресу " + Utils.toHex(addr, 11) + ": " + Utils.toHex(cpu.getMemory().getValue(addr), 16));
                    continue;
                }

                if (checkCmd(cmd, "wto")) {
                    if (i == cmds.length - 1) {
                        throw new Exception("команда rfrom требует аргумент адреса");
                    }

                    long addr = Integer.parseInt(cmds[++i], 16);

                    if (i == cmds.length - 1) {
                        throw new Exception("команда rfrom требует аргумент значение");
                    }

                    long valueToSet = Integer.parseInt(cmds[++i], 16);
                    cpu.getMemory().setValue(addr, valueToSet);
                    println(
                            "Значение " +
                                    Utils.toHex(valueToSet, 16) +
                                    " было записано по адресу " +
                                    Utils.toHex(addr, 11)
                    );

                    continue;
                }

                if (checkCmd(cmd, "accesses")) {
                    printMemoryAccesses = !printMemoryAccesses;
                    println("Вывод доступов к памяти " + (printMemoryAccesses ? "включен" : "выключен"));
                    continue;
                }

                if (checkCmd(cmd, "exe")) {
                    Thread th = new Thread(() -> {
                        try {
                            Thread.sleep(200);
                            checkResult(cpu.startContinue());

                            do {
                                fastExecution();
                            } while (cpu.getRegister(Reg.CR).getValue() != 0x100);

                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    th.start();
                    continue;
                }

                if (checkCmd(cmd, "load")) {
                    loadToMemory();
                    continue;
                }

                if (checkCmd(cmd, "asm") || checkCmd(cmd, "assembler")) {
                    String code = "";

                    println("Введите текст программы. Для окончания введите END");

                    for (; ; ) {
                        line = fetchLine();

                        if (line.equalsIgnoreCase("END")) {
                            break;
                        }

                        code = code.concat(line.concat("\n"));
                    }

                    printOnStop = false;
                    AsmNg asm = new AsmNg(code);
                    Program pobj = asm.compile();
                    if (asm.getErrors().isEmpty()) {
                        ProgramBinary prog = new ProgramBinary(pobj.getBinaryFormat());
                        bcomp.loadProgram(prog);
                        println("Программа начинается с адреса " + Utils.toHex(prog.start_address, 11));
                    } else {
                        for (String err : asm.getErrors()) {
                            println(err);
                        }
                        println("Программа содержит ошибки");
                    }
                    printOnStop = true;
                    continue;
                }

                if (checkCmd(cmd, "sleep")) {
                    if (i == cmds.length - 1) {
                        throw new Exception("команда sleep требует аргумент");
                    }

                    sleeptime = Integer.parseInt(cmds[++i], 16);
                    continue;
                }
            } catch (Exception e) {
                printOnStop = true;
                println("Ошибка: " + e.getMessage());
                continue;
            }

            try {
                if (Utils.isHexNumeric(cmd) && cmd.length() <= (cpu.getRegWidth(Reg.IR) / 4) + (cmd.charAt(0) == '-' ? 1 : 0)) {
                    value = Integer.parseInt(cmd, 16);
                    cpu.getRegister(Reg.IR).setValue(value);
                } else {
                    println("Неизвестная команда " + cmd);
                }
            } catch (Exception e) {
                println("Неизвестная команда " + cmd);
            }
        }
    }

    private void fastExecution() throws Exception {
        try {
            checkResult(cpu.executeContinue());
        } catch (Exception e) {
            Thread.sleep(2);
        }
    }

    @SuppressWarnings("WeakerAccess")
    protected String fetchLine() throws Exception {
        return input.nextLine();
    }

    @SuppressWarnings("WeakerAccess")
    protected void print(String str) {
        System.out.print(str);
    }

    @SuppressWarnings("WeakerAccess")
    protected void println(String str) {
        System.out.println(str);
    }
}
