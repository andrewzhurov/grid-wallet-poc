# grID Wallet POC

This is a Proof-of-Concept of a KERI Wallet for local-first environment, testing an approach of Group AID management via a Hashgraph/Blocklace/Merkle-DAG of device communication and a distributed consensus algorithm run by each member peer. https://github.com/andrewzhurov/hashgraph

Note: there are plenty of mocked things. No signing is done aside from DIDComm messages. A makeshift KERI-like impl is in use. Not a production-lined project, but a testbed for ideas.

AID<->AID communication is presently facilitated by DIDComm, this repo makes use of https://github.com/decentralized-identity/didcomm-demo
In theory, isn't a hard dependency, some sort of a mailbox is desired.

Features:
Connect Invite / Connect Invite Accepted to establish AID<->AID connection.
AID<->AID communication, where each member device participates.
Merkle-DAG of communication, cordially synced between devices. As in
Whatsapp-like Group Topics with arbitrary amount of Member AIDs.
Ability to convert a Group to a Group AID.
Group Membership.
p2p disclosure of novel KEs.
Control Propagation from Parent AID -> Child AID.
Long-lasting issuance of ACDCs / processing proposals.
Some junky UI.:)


Some tech details:
State is mostly kept in `app.state/*topic-path->tip-taped` atom.
Which stores the last event for each `topic-path`.
(as device may be in the same `topic` under different identities, we need to track `topic-path`->`tip`, not `topic`->`tip`)
`taped` means `tip` comes with metadata of novel, subjectively ordered, received events.
Handy for maintainance of subjective views, such as feeds of messages.

Most of other things are derived out of `*topic-path->tip-taped`.


## Running the Application

```bash
npm install                     # Or `yarn install`
npm run build                   # Or `yarn build`; to build DIDComm's worker.js via webpack
npx tsc -p ./cljs-tsconfig.json # to compile some DIDComm .ts modules to .js (API used from .cljs)

# For some reason, compilations without a server fail.
# And with server, the first one will fail, subsequent succeed.
npx shadow-cljs server       # Start shadow-cljs server
npx shadow-cljs release :app # Requests server to compile prod version of the app -> fails
npx shadow-cljs release :app # Requests server to compile prod version of the app -> expected to succeed

# Serve the app in any way you prefer, e.g.,
python3 -m http.server -d public/
# Open in your browser the url, e.g., http://localhost:8000, for the above server
```

Alternatively, you could connect to shadow-cljs server in watch mode via an editor, see the respective [editor integration](https://shadow-cljs.github.io/docs/UsersGuide.html#_editor_integration).
(re-compiles on .cljs file changes)


## License
This POC started as a fork of https://github.com/decentralized-identity/didcomm-demo,
all its code (up to f5383e1, including) is [MIT](LICENSE.md).
All further commits of this repo is [Apache 2.0](LICENSE-APACHE.md).
