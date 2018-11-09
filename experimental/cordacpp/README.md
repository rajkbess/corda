# Corda C++ decoding support

This code demonstrates how to read Corda serialized AMQP messages without any Java, 
just using C++14. It exists primarily to show the cut-lines for reimplementors, and
to help people explore the wire format at a low level.

You will need to install Apache Qpid Proton C++ and cmake to be able to compile this.

Additionally you will need to run the included Kotlin tool to generate C++ header files
from annotated classes. Run it with command line arguments like this:

`include net.corda.core.transactions.WireTransaction net.corda.core.transactions.SignedTransaction`

The first argument is the directory where the generated code wille be placed.

# TODO

- [ ] Can't yet parse a SignedTransaction due to some descriptor / context mismatch issue
- [ ] Enums
- [ ] Evolution
- [ ] Back-references
- [ ] Serialization not just deserialization
- [ ] Demonstrate how to calculate the Merkle tree
- [ ] Demonstrate how to check the signatures