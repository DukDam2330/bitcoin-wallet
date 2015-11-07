/*
 * Copyright 2011-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.pop;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PopIntent;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.HelpDialogFragment;
import de.schildbach.wallet.ui.TransactionsAdapter;
import de.schildbach.wallet.util.Toast;
import de.schildbach.wallet_test.R;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;
import se.rosenbaum.jpop.Pop;
import se.rosenbaum.jpop.PopRequestURI;
import se.rosenbaum.jpop.generate.HttpPopSender;
import se.rosenbaum.jpop.generate.PopGenerationException;
import se.rosenbaum.jpop.generate.PopGenerator;
import se.rosenbaum.jpop.generate.PopSender;
import se.rosenbaum.jpop.generate.PopSigningException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * @author Kalle Rosenbaum
 */
public class PopActivity extends AbstractWalletActivity
{
	private static final Logger log = LoggerFactory.getLogger(PopActivity.class);

	public static final String INTENT_EXTRA_POP_INTENT = "pop_intent";

	private PopRequestURI popRequestURI;
	private Transaction transactionToProve;
	private Button proveButton;
	private State currentState = null;

	private enum State
	{
		INPUT(R.string.pop_send_pop), // asks for confirmation
		DECRYPTING(R.string.send_coins_fragment_state_decrypting),
		SIGNING(R.string.send_coins_preparation_msg),
		SENDING(R.string.send_coins_sending_msg),
		SENT(R.string.pop_sent),
		SUCCESS(R.string.pop_proven),
		FAILED(R.string.send_coins_failed_msg); // sending
																																	// states
		private final int resId;

		State(final int resId)
		{
			this.resId = resId;
		}

		private int getResId()
		{
			return resId;
		}
	}

	public static void start(final Context context, final PopIntent popIntent)
	{
		final Intent intent = new Intent(context, PopActivity.class);
		intent.putExtra(INTENT_EXTRA_POP_INTENT, popIntent);
		context.startActivity(intent);
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setContentView(R.layout.pop_content);

		// We can come here either from an intent filter in AndroidManifest.xml or from
		// WalletActivity.onActivityResult after scanning
		// If from intent filter, we have a Uri. If from WalletActivity, we have a PopIntent.
		PopIntent popIntent = getIntent().getParcelableExtra(INTENT_EXTRA_POP_INTENT);
		if (popIntent == null)
		{
			// We are started from the intent filter
			String input = getIntent().getData().toString();
			try
			{
				popIntent = PopIntent.fromPopRequestURI(new PopRequestURI(input));
			}
			catch (IllegalArgumentException e)
			{
				log.info("Got invalid btcpop uri: '" + input + "'", e);
				resultMessage(false, R.string.pop_input_parser_invalid_btcpop_uri, input);
				finish();
				return;
			}
		}
		popRequestURI = popIntent.getPopRequestURI();

		WalletApplication walletApplication = getWalletApplication();
		Wallet wallet = walletApplication.getWallet();

		List<Transaction> transactions = wallet.getTransactionsByTime();
		for (Transaction transaction : transactions)
		{
			if (popRequestURI.getTxid() != null && !transaction.getHash().equals(popRequestURI.getTxid()))
			{
				continue;
			}
			Coin value = transaction.getValue(wallet);
			if (popRequestURI.getAmountSatoshis() != null && value.longValue() != popRequestURI.getAmountSatoshis())
			{
				continue;
			}
			if (popRequestURI.getLabel() != null && !popRequestURI.getLabel().equals(transaction.getMemo()))
			{
				continue;
			}
			transactionToProve = transaction;
			break;
		}

		if (transactionToProve == null)
		{
			de.schildbach.wallet.util.Toast toast = new de.schildbach.wallet.util.Toast(this);
			// This wallet doesn't have a transaction with m
			StringBuilder stringBuilder = new StringBuilder(popRequestURI.getTxid() != null ? "txid=" + popRequestURI.getTxid() + ", " : "");
			stringBuilder.append(popRequestURI.getTxid() != null ? "txid=" + popRequestURI.getTxid() + ", " : "");
			stringBuilder.append(popRequestURI.getLabel() != null ? "label=" + popRequestURI.getLabel() + ", " : "");
			stringBuilder.append(popRequestURI.getMessage() != null ? "message=" + popRequestURI.getMessage() + ", " : "");
			stringBuilder.append(popRequestURI.getAmountSatoshis() != null ? "amount="
					+ Coin.valueOf(popRequestURI.getAmountSatoshis()).toPlainString() + ", " : "");
			if (stringBuilder.length() > 1)
			{
				stringBuilder.delete(stringBuilder.length() - 2, stringBuilder.length());
			}
			toast.longToast(R.string.pop_no_matching_transaction, stringBuilder.toString());
			finish();
			return;
		}

		URL url = getUrl(popRequestURI.getP());
		if (url == null)
		{
			return;
		}
		setText(R.id.pop_destination, url.getHost());
		TextView destinationView = getView(R.id.pop_destination);
		String protocol = url.getProtocol();
		if ("https".equals(protocol))
		{
			destinationView.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_secure, 0, 0, 0);
			findViewById(R.id.pop_insecure_warning).setVisibility(View.GONE);
		}
		else
		{
			destinationView.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_partial_secure, 0, 0, 0);
			findViewById(R.id.pop_insecure_warning).setVisibility(View.VISIBLE);
		}

		TextView viewCancel = (Button) findViewById(R.id.send_coins_cancel);
		viewCancel.setText(R.string.button_cancel);
		viewCancel.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(final View v)
			{
				finish();
			}
		});
		proveButton = (Button) findViewById(R.id.send_coins_go);
		proveButton.setText(R.string.button_cancel);
		proveButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(final View v)
			{
				sendPop(v);
			}
		});

		FrameLayout sentTransactionView = (FrameLayout) findViewById(R.id.pop_transaction_to_prove);
		TransactionsAdapter adapter = new TransactionsAdapter(this, wallet, false, walletApplication.maxConnectedPeers(), null);
		adapter.replace(transactionToProve);
		RecyclerView.ViewHolder sentTransactionViewHolder = adapter.createTransactionViewHolder(sentTransactionView);
		sentTransactionView.addView(sentTransactionViewHolder.itemView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		adapter.setFormat(getWalletApplication().getConfiguration().getFormat());
		adapter.bindViewHolder(sentTransactionViewHolder, 0);

		updateState(State.INPUT);
	}

	private void setText(final int resourceId, final String text)
	{
		TextView textView = getView(resourceId);
		textView.setText(text);
	}

	private TextView getView(final int resourceId)
	{
		TextView textView = (TextView) findViewById(resourceId);
		if (textView == null)
		{
			throw new IllegalArgumentException("Resource id " + resourceId + " not found.");
		}
		return textView;
	}

	private URL getUrl(final String pParam)
	{
		URL url;
		try
		{
			url = new URL(pParam);
		}
		catch (MalformedURLException e)
		{
			Toast toast = new Toast(this);
			toast.longToast("Not a proper destination URL:" + pParam);
			finish();
			return null;
		}
		return url;
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu)
	{
		getMenuInflater().inflate(R.menu.send_coins_activity_options, menu);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case android.R.id.home:
				finish();
				return true;

			case R.id.send_coins_options_help:
				HelpDialogFragment.page(getFragmentManager(), R.string.help_pop);
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	public void sendPop(final View view)
	{
		View privateKeyBadPasswordView = findViewById(R.id.send_coins_private_key_bad_password);
		privateKeyBadPasswordView.setVisibility(View.INVISIBLE);

		SendPopTask sendPopTask = new SendPopTask();
		sendPopTask.execute(popRequestURI);
	}

	public void resultMessage(final boolean success, final int resId, final Object... formatArgs)
	{
		TextView popMessage = getView(R.id.pop_message);

		if (success)
		{
			Toast toast = new Toast(this);
			toast.longToast(resId, formatArgs);
			finish();
		}
		else
		{
			popMessage.setTextColor(getResources().getColor(R.color.fg_error));
			popMessage.setVisibility(View.VISIBLE);
			popMessage.setText(getString(resId, formatArgs));
		}
	}

	public void resultMessage(final String message)
	{
		TextView popMessage = getView(R.id.pop_message);

		popMessage.setTextColor(getResources().getColor(R.color.fg_error));
		popMessage.setVisibility(View.VISIBLE);
		popMessage.setText(message);
	}

	private void updateState(final State newState)
	{
		proveButton.setText(newState.getResId());
		final boolean privateKeyPasswordViewVisible = getWalletApplication().getWallet().isEncrypted();
		View pinGroup = findViewById(R.id.send_coins_private_key_password_group);
		pinGroup.setVisibility(privateKeyPasswordViewVisible ? View.VISIBLE : View.GONE);
		View privateKeyPasswordView = findViewById(R.id.send_coins_private_key_password);
		privateKeyPasswordView.setEnabled(newState == State.INPUT);
	}

	private class Outcome
	{
		PopSender popSender;
		Exception exception;
	}

	private class SendPopTask extends AsyncTask<PopRequestURI, State, Outcome>
	{

		@Override
		protected Outcome doInBackground(final PopRequestURI... popRequestURIs)
		{
			return sendPop(popRequestURIs[0]);
		}

		private KeyParameter getKeyParameter()
		{
			Wallet wallet = getWalletApplication().getWallet();
			if (!wallet.isEncrypted())
			{
				return null;
			}
			KeyCrypter keyCrypter = wallet.getKeyCrypter();
			if (keyCrypter == null)
			{
				return null;
			}
			TextView privateKeyPasswordView = (TextView) findViewById(R.id.send_coins_private_key_password);
			return keyCrypter.deriveKey(privateKeyPasswordView.getText().toString().trim());
		}

		@Override
		protected void onProgressUpdate(final State... values)
		{
			for (State value : values)
			{
				updateState(value);
			}
		}

		protected void onPostExecute(final Outcome outcome)
		{
			Exception exception = outcome.exception;
			if (exception != null)
			{
				if (exception instanceof KeyCrypterException
						|| (exception instanceof PopSigningException && ((PopSigningException) exception).isBadDecryptionKey()))
				{
					updateState(State.INPUT);

					View privateKeyBadPasswordView = findViewById(R.id.send_coins_private_key_bad_password);
					privateKeyBadPasswordView.setVisibility(View.VISIBLE);

					TextView privateKeyPasswordView = (TextView) findViewById(R.id.send_coins_private_key_password);
					privateKeyPasswordView.requestFocus();
				}
				else
				{
					resultMessage(exception.getMessage());
				}
				return;
			}

			PopSender popSender = outcome.popSender;
			PopSender.Result result = popSender.getResult();
			if (result == PopSender.Result.OK)
			{
				updateState(State.SUCCESS);
				resultMessage(true, R.string.pop_sent_success);
			}
			else
			{
				updateState(State.FAILED);
				String errorMessage = popSender.errorMessage();
				resultMessage(false, result == PopSender.Result.INVALID_POP ? R.string.pop_send_invalid_pop : R.string.pop_send_failed,
						errorMessage == null ? "No message" : errorMessage);
			}
		}

		private Outcome sendPop(final PopRequestURI popRequestURI)
		{
			Outcome outcome = new Outcome();
			try
			{
				publishProgress(State.DECRYPTING);
				final KeyParameter encryptionKey = getKeyParameter();
				PopGenerator popGenerator = new PopGenerator();
				Pop pop = popGenerator.createPop(transactionToProve, popRequestURI.getN());
				publishProgress(State.SIGNING);
				popGenerator.signPop(pop, getWalletApplication().getWallet(), encryptionKey);
				HttpPopSender popSender = new HttpPopSender(popRequestURI);
				publishProgress(State.SENDING);
				popSender.sendPop(pop);
				outcome.popSender = popSender;
			}
			catch (PopGenerationException e)
			{
				publishProgress(State.FAILED);
				log.debug("Couldn't create PoP", e);
				outcome.exception = e;
			}
			catch (PopSigningException e)
			{
				publishProgress(State.FAILED);
				log.debug("Couldn't sign PoP", e);
				outcome.exception = e;
			}
			catch (KeyCrypterException e)
			{
				publishProgress(State.INPUT);
				outcome.exception = e;
			}
			return outcome;
		}
	}
}