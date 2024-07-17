import { DEFAULT_MEDIATOR } from "../constants"
import { Profile } from "./profile"
import { default as ContactService, Contact, Message } from "./contacts"
import { WorkerCommand, WorkerMessage } from "./workerTypes"
import eventbus, { EventListenerHandle } from "./eventbus"
import { IMessage } from "didcomm"
import { DIDCommMessage, DID } from "./didcomm"

export interface AgentMessage {
  sender: Contact
  receiver: Contact
  message: IMessage
}

const IMPLEMENTED_PROTOCOLS = [
  "https://didcomm.org/discover-features/2.0",
  "https://didcomm.org/trust-ping/2.0",
  "https://didcomm.org/basicmessage/2.0",
  "https://didcomm.org/user-profile/1.0",
]


interface onMessageMap {
  [key: string]: Function
}

const createAgent = (ondid: Function, onconnected: Function, ondisconnected: Function, onmessage: Function) => {
  let worker = new Worker("./js/worker.js")

  const postWorkerMessage = (message: Partial<WorkerCommand<any>>) => {
    worker.postMessage(message)
  }

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

  const onWorkerMessage = (e: MessageEvent<WorkerMessage<any>>) => {
    console.log("Agent received worker message: ", e.data.type)
    switch (e.data.type) {
      case "init":
        postWorkerMessage({
          type: "establishMediation",
          payload: { mediatorDid: DEFAULT_MEDIATOR },
        })
        break
      case "didGenerated":
        ondid(e.data.payload)
        break
      case "connected":
        onconnected()
        break
      case "disconnected":
        ondisconnected()
        break
      case "messageReceived":
        onmessage(e.data.payload)
        break
      case "error":
      default:
        console.log("Unhandled message: ", e.data)
    }
  }
  worker.onmessage = onWorkerMessage


  const sendMessage = async (to: DID, message: DIDCommMessage) => {
    postWorkerMessage({
      type: "sendMessage",
      payload: { to, message },
    })
  }

  return {
    connect,
    disconnect,
    sendMessage,
  }
}

export class Agent {
  public profile: Profile
  private worker: Worker

  constructor() {
    let onMessage:onMessageMap = {}

    const onProfileUpdate = async (message: AgentMessage) => {
      let contact = ContactService.getContact(message.message.from)
      if (!contact) {
        return
      }

      let label = message.message.body?.profile?.displayName
      if (!label) {
        return
      }

      contact.label = label
      ContactService.addContact(contact)
    }
    onMessage["https://didcomm.org/user-profile/1.0/profile"] = onProfileUpdate


    const onProfileRequest = async (message: AgentMessage) => {
      let contact = ContactService.getContact(message.message.from)
      if (!contact) {
        return
      }
      await this.sendProfile(contact)
    }
    onMessage["https://didcomm.org/user-profile/1.0/request-profile"] = onProfileRequest


    const handlePing = (message: IMessage) => {
      if (message.body?.response_requested !== false) {
        this.sendMessage(message.from, {
          type: "https://didcomm.org/trust-ping/2.0/ping-response",
          thid: message.id,
        })
      }
    }
    onMessage["https://didcomm.org/trust-ping/2.0/ping"] = handlePing

    const handleDiscoverFeatures = (message: IMessage) => {
      const discloseMessage = this.discoverFeatures(message)
      this.sendMessage(message.from, discloseMessage)
    }
    onMessage["https://didcomm.org/discover-features/2.0/queries"] = handleDiscoverFeatures

    const onMessageReceived = (message: IMessage) => {
      const from = // from Contact ~_~
        message.from == this.profile.did
          ? (this.profile as Contact)
          : ContactService.getContact(message.from)
      const to =
        message.to[0] == this.profile.did
          ? (this.profile as Contact)
          : ContactService.getContact(message.to[0])

      if (ContactService.getContact(message.from)) {
        let fromName = message.from
        if (from) {
          fromName = from.label || from.did
        }
        ContactService.addMessage(message.from, {
          sender: fromName,
          receiver: to.label || to.did,
          timestamp: new Date(),
          content: message.body.content,
          type: message.type,
          raw: message,
        })
      }

      const onMessageFn = onMessage[message.type]
      onMessageFn ? onMessageFn({ sender: from, receiver: to, message })
        : console.warn(`Did not find message handler for message type: ${message.type}`)
    }


    const onWorkerMessage = (e: MessageEvent<WorkerMessage<any>>) => {
      console.log("Agent received message: ", e.data.type)
      switch (e.data.type) {
        case "log":
          break
        case "init":
          postMessage({
            type: "establishMediation",
            payload: { mediatorDid: DEFAULT_MEDIATOR },
          })
          break
        case "didGenerated":
          ondid(e.data.payload)
          break
        case "connected":
          onconnected("connected")
          break
        case "disconnected":
          ondisconnected("disconnected")
          break
        case "messageReceived":
          onMessageReceived(e.data.payload)
          break
        case "error":
        default:
          console.log("Unhandled message: ", e.data)
      }
    }
    this.worker.onmessage = onWorkerMessage
}

  // setupProfile(profile: Profile) {
  //   this.profile = profile
  // }


  set ondisconnect(callback: () => void) {
    eventbus.on("disconnected", callback)
  }




  public async refreshMessages() {
    this.postMessage({
      type: "pickupStatus",
      payload: { mediatorDid: DEFAULT_MEDIATOR },
    })
  }


  public async sendProfile(contact: Contact) {
    const message = {
      type: "https://didcomm.org/user-profile/1.0/profile",
      body: {
        profile: {
          displayName: this.profile.label,
        },
      },
    }
    await this.sendMessage(contact, message as IMessage)
  }




  public async requestProfile(contact: Contact) {
    const message = {
      type: "https://didcomm.org/user-profile/1.0/request-profile",
      body: {
        query: ["displayName"],
      },
    }
    await this.sendMessage(contact, message as IMessage)
  }




  public async sendFeatureDiscovery(contact: Contact) {
    const message = {
      type: "https://didcomm.org/discover-features/2.0/queries",
      body: {
        queries: [
          {
            "feature-type": "protocol",
            match: "https://didcomm.org/*",
          },
        ],
      },
    }
    await this.sendMessage(contact, message as IMessage)
  }

  private discoverFeatures(message: IMessage) {
    const regexEscape = (s: string) =>
      s.replace(/([.*+?$^=!:{}()|\[\]\/\\])/g, "\\$1")
    const createRegex = (query: string) =>
      new RegExp(`^${query.split("*").map(regexEscape).join(".*")}$`)
    let protocolResponse: object[] = []

    // Loop through all queries, then all implemented protocols and build up a
    // list of supported protocols that match the user's request
    for (let query of message.body.queries) {
      // Rudimentary implementation, ignoring all except protocol requests
      if (query["feature-type"] != "protocol") continue

      for (let protocol of IMPLEMENTED_PROTOCOLS) {
        if (createRegex(query["match"]).test(protocol)) {
          protocolResponse.push({
            "feature-type": "protocol",
            id: protocol,
          })
        }
      }
    }
    const response = {
      type: "https://didcomm.org/discover-features/2.0/disclose",
      thid: message.id,
      body: {
        disclosures: protocolResponse,
      },
    }
    return response
  }

}

export default new Agent()
