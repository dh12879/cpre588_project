/****************************************************************************
*  Title: rpcserver.sc
*  Author: Team 4
*  Date: 05/06/2015
*  Description: Remote Procedure Call (RPC) Server Behavior
****************************************************************************/

#include <stdio.h>
#include "coreapi.h"

import "c_double_handshake";	// import the standard double handshake channel
import "c_mutex";	            // import the standard mutex channel

behavior RPCServer (i_receiver c_request, i_sender c_response,
  Blockchain *blockchain, TransactionPool *transaction_pool,
  in int target_threshold, in event start_servers, i_semaphore block_mutex,
  i_semaphore pool_mutex)
{

  void create_block_template (RPCMessage *packet)
  {
    int idx;
    BlockTemplate *p;
    p = &(packet.data.blocktemplate);

    p->version = BLOCK_VERSION;

    block_mutex.acquire();
    p->previous_block_hash = blockchain->entries[blockchain->head_block].hash;
    block_mutex.release();

    pool_mutex.acquire();
    for (idx = 0; idx < transaction_pool->n_in_pool; idx++)
    {
      // Copy transaction from pool into packet payload
      memcpy(&(p->transactions[idx]), &(transaction_pool->pool[idx]), sizeof(Transaction));
    }
    transaction_pool->n_in_pool = 0;
    pool_mutex.release();

    p->current_time = (int)time(0);
    p->bits = target_threshold;
  }

  void build_utxo_set (RPCMessage *packet)
  {
    // TODO: Complete this function
  }

  void build_txout (RPCMessage *packet)
  {
    int idx1, idx2;

    block_mutex.acquire();

    // Search for the output in the blockchain
    for (idx1 = 0; idx1 <= blockchain->head_block; idx1++)
    {
      for (idx2 = 0; idx2 < blockchain->entries[idx1].n_transactions)
      {
        if (blockchain->entries[idx1].transactions[idx2].txid == packet.data.txout.txid)
        {
          // Copy the transaction output data
          packet.data.txout.best_block = blockchain->entries[idx1].hash;
          packet.data.txout.value = blockchain->entries[idx1].transactions[idx2].amount;
          packet.data.txout.address = blockchain->entries[idx1].transactions[idx2].address;
          break;
        }
      }
      break;
    }

    block_mutex.release();
  }

  void main (void)
  {
    RPCMessage packet;

    // A local transaction copy used for signing a transaction and adding it
    // to the pool. This is a design simplification to reduce scope.
    Transaction local_transaction_copy;

    // Wait for the blockchain to initialize
    wait(start_servers);

    while (1)
    {
      // Wait for an RPC network packet
      c_request.receive(&packet, sizeof(packet));

      // Read the header and make an appropriate response
      switch (packet.type)
      {
        case GET_BLOCK_TEMPLATE_REQ:
        {
          // Create a block template payload
          create_block_template(&packet);
          break;
        }
        case GET_BLOCK_COUNT_REQ:
        {
          // Create a block count payload
          packet.block_count = head_block+1;
          break;
        }
        case SUBMIT_BLOCK:
        {
          // TODO: validate block?
          // TODO: transmit block to the network?
          // Add the new block to the blockchain
          block_mutex.acquire();
          blockchain->head_block++;
          memcpy(&(blockchain->entries[blockchain->head_block]),
            &(packet.data.block), sizeof(Block));
          block_mutex.release();
        }
        case GET_TX_OUT:
        {
          break;
        }
        case GET_TX_OUT_SET_INFO:
        {
          break;
        }
        case GET_TX_OUT_SET_INFO:
        {
          build_utxo_set(&packet);
          break;
        }
        case GET_TX_OUT:
        {
          build_txout(&packet);
          break;
        }
        case CREATE_RAW_TRANSACTION:
        {
          // Use the current time for the txid
          packet.data.transaction.txid = (int)time(0);

          // Only one output allowed to reduce scope
          packet.data.transaction.output.txid = packet.data.transaction.txid;
          packet.data.transaction.output.vout = 0;

          // Create representative raw transaction by summing id and address
          packet.data.transaction.raw_transaction = packet.data.transaction.txid + address;

          // Create a local copy of the transaction
          memcpy(&local_transaction_copy, &(packet.data.transaction), sizeof(Transaction));
          break;
        }
        case SIGN_RAW_TRANSACTION:
        {
          if (packet.data.transaction.raw_transaction == local_transaction_copy.raw_transaction)
          {
            // Create representative signed transaction by summing raw transaction and key
            packet.data.transaction.signed_transaction = packet.data.transaction.raw_transaction +
              packet.data.transaction.private_key;

            // Update local copy of transaction;
            local_transaction_copy.private_key = packet.data.transaction.private_key;
            local_transaction_copy.signed_transaction = packet.data.transaction.signed_transaction;
          }
          else
          {
            fprintf(stderr, "Couldn't sign raw transaction\n");
            exit (1);
          }
          break;
        }
        case SEND_RAW_TRANSACTION:
        {
          if (packet.data.transaction.signed_transaction == local_transaction_copy.signed_transaction)
          {
            // Add the transaction to the transaction pool
            pool_mutex.acquire();
            if ((transaction_pool->n_in_pool) < MAX_TRANSACTIONS)
            {
              memcpy(&(transaction_pool->pool[transaction_pool->n_in_pool]),
                &(local_transaction_copy), sizeof(Transaction));
              transaction_pool->n_in_pool++;
            }
            pool_mutex.release();
          }
          else
          {
            fprintf(stderr, "Couldn't send signed transaction\n");
            exit (1);
          }
          break;
        }
        default:
        {
          fprintf(stderr, "Core received invalid RPC message\n");
          break;
        }

        // Send the packet over the network
        c_response.send(&packet, sizeof(packet));
      }
    }
  }

};