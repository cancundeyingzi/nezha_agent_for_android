package com.nezhahq.agent.mcp;

/** A real child process for argument, dual-pipe and stdin behaviour. No Android/mock dependencies. */
public final class ExecFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "args":
                System.out.print(args[1]);
                break;
            case "dual":
                byte[] output = new byte[180_000];
                java.util.Arrays.fill(output, (byte) 'o');
                System.out.write(output);
                java.util.Arrays.fill(output, (byte) 'e');
                System.err.write(output);
                break;
            case "stdin":
                int total = 0;
                byte[] input = new byte[1024];
                int count;
                while ((count = System.in.read(input)) != -1) total += count;
                System.out.print(total);
                break;
            case "no-stdin":
                System.out.print("started");
                System.out.flush();
                Thread.sleep(30_000);
                break;
            case "nonzero":
                System.out.print("out");
                System.err.print("err");
                System.exit(7);
                break;
            case "close-stdin":
                System.in.close();
                Thread.sleep(100);
                System.exit(13);
                break;
            default:
                throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
