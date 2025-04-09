import { DEFAULT_MEDIATOR } from "../constants"
import { WorkerCommand, WorkerMessage } from "./workerTypes"
import { DIDCommMessage, DID } from "./didcomm"

interface AgentConfig {
  ondid: Function,
  onconnected: Function,
  ondisconnected: Function,
  onmessage: Function,
  ondiddoc: Function,
}

interface WorkerMessageHandlers {
  [key: string]: Function;
}

export const createAgent = ({ ondid, ondiddoc, onconnected, ondisconnected, onmessage}: Partial<AgentConfig>) => {
  let worker = new Worker("./js/worker.js")

  const postWorkerMessage = (message: Partial<WorkerCommand<any>>) => {
    worker.postMessage(message)
  }

  const workerEstablishMediation = () => {
    postWorkerMessage({
      type: "establishMediation",
      payload: { mediatorDid: DEFAULT_MEDIATOR },
    })
  }

  const workerMessageHandlers: WorkerMessageHandlers = {
    init: workerEstablishMediation,
    didGenerated: ondid,
    connected: onconnected,
    disconnected: ondisconnected,
    messageReceived: onmessage,
    resolvedDIDDoc: ondiddoc,
  }

  const onWorkerMessage = (workerMessage: MessageEvent<WorkerMessage<any>>) => {
    let workerMessageHandler = workerMessageHandlers[workerMessage.data.type]
    let payload = workerMessage.data.payload

    if (!workerMessageHandler) {
      console.warn(`No handler registered for worker message type: ${workerMessage.data.type}`)
      return
    }

    payload ? workerMessageHandler(payload) : workerMessageHandler()
  }
  worker.onmessage = onWorkerMessage


  const connect = async () => {
    postWorkerMessage({
      type: "connect",
      payload: { mediatorDid: DEFAULT_MEDIATOR },
    })
  }

  const disconnect = async () => {
    postWorkerMessage({
      type: "disconnect",
    })
  }

  const sendMessage = async (to: DID, message: DIDCommMessage) => {
    postWorkerMessage({
      type: "sendMessage",
      payload: { to, message },
    })
  }

  const resolveDIDDoc = async (did: DID) => {
    postWorkerMessage({
      type: "resolveDIDDoc",
      payload: did
    })
  }

  return {
    worker,
    connect,
    disconnect,
    sendMessage,
    resolveDIDDoc,
  }
}
