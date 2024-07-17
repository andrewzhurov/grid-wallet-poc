(ns lib.worker
  (:require ["@noble/curves/ed25519" :refer [ed25519
                                             edwardsToMontgomeryPub
                                             edwardsToMontgomeryPriv]]
            ["didcomm"
             :refer [Message]]))
