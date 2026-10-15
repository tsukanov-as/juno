package juno.runtime;

import java.io.IOException;

/** Internal byte destination; implementations validate owner, direction and open state. */
interface BinarySink {
    void writeBinary(byte[] bytes,int offset,int count) throws IOException;
    static void write(BinarySink sink,byte[] bytes) throws IOException {
        Execution.checkCancellation();
        if(bytes.length==0) sink.writeBinary(bytes,0,0); // Empty data still validates the destination.
        for(int offset=0;offset<bytes.length;) {
            Execution.checkCancellation(); int count=Math.min(65536,bytes.length-offset);
            sink.writeBinary(bytes,offset,count); offset+=count;
        }
        Execution.checkCancellation();
    }
}
