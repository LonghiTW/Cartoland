package cartoland.commands;

import cartoland.utilities.CommonFunctions;
import cartoland.utilities.JsonHandle;
import cartoland.utilities.TimerHandle;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/**
 * {@code BirthdayCommand} is an execution when a user uses /birthday command. This class implements {@link ICommand}
 * interface, which is for the commands HashMap in {@link cartoland.events.CommandUsage}. This class doesn't
 * handle sub commands, but call other classes to deal with it. Thought this command has subcommands, it
 * doesn't extend {@link HasSubcommands} class, this is because this command only has 2 subcommands, which can
 * be determined using a single {@link String#equals(Object)} method.
 *
 * @since 2.1
 * @author Alex Cai
 */
public class BirthdayCommand extends HasSubcommands
{
	public BirthdayCommand()
	{
		super(3);
		subcommands.put("set", new SetSubCommand());
		subcommands.put("get", event ->
		{
			User user = event.getUser();
			User target = event.getOption("target", CommonFunctions.getAsUser);
			if (target == null)
				target = user;
			int[] birthday = TimerHandle.getBirthday(target.getIdLong());
			long userID = user.getIdLong();
			if (birthday != null)
				event.reply(
						JsonHandle.getStringFromJsonKey(userID, "birthday.get.set_on")
								.formatted(
										target.getEffectiveName(),
										JsonHandle.getStringFromJsonKey(userID, "birthday.month_" + birthday[0]),
										JsonHandle.getStringFromJsonKey(userID, "birthday.date_" + birthday[1])))
						.queue();
			else
				event.reply(JsonHandle.getStringFromJsonKey(userID, "birthday.get.no_set").formatted(target.getEffectiveName())).queue();
		});
		subcommands.put("delete", event ->
		{
			long userID = event.getUser().getIdLong();
			TimerHandle.deleteBirthday(userID);
			event.reply(JsonHandle.getStringFromJsonKey(userID, "birthday.delete")).queue();
		});
	}

	/**
	 * {@code SetSubCommand} is a class that handles one of the subcommands of {@code /birthday} command, which is
	 * {@code /birthday set}.
	 *
	 * @since 2.1
	 * @author Alex Cai
	 */
	private static class SetSubCommand implements ICommand
	{
		@Override
		public void commandProcess(SlashCommandInteractionEvent event)
		{
			Integer monthBox = event.getOption("month", CommonFunctions.getAsInt);
			Integer dateBox = event.getOption("date", CommonFunctions.getAsInt);
			if (monthBox == null || dateBox == null)
			{
				event.reply("Impossible, this is required!").queue();
				return;
			}

			long userID = event.getUser().getIdLong();
			int month = monthBox, date = dateBox;
			if (month < 1 || month > 12) //月份不在1 ~ 12的區間
			{
				event.reply(JsonHandle.getStringFromJsonKey(userID, "birthday.set.wrong_month")).setEphemeral(true).queue();
				return;
			}

			if (date < 1 || date > 31) //日期不在1 ~ 31的區間
			{
				event.reply(JsonHandle.getStringFromJsonKey(userID, "birthday.set.wrong_date")).setEphemeral(true).queue();
				return;
			}

			if (isWrongDate(month, date)) //如果日期對不上月份 例如2月30日、4月31日等
			{
				event.reply(JsonHandle.getStringFromJsonKey(userID, "birthday.set.wrong_date_in_month")
									.formatted(
											JsonHandle.getStringFromJsonKey(userID, "birthday.month_" + month),
											JsonHandle.getStringFromJsonKey(userID, "birthday.date_" + date))).setEphemeral(true).queue();
				return;
			}

			event.reply(JsonHandle.getStringFromJsonKey(userID, "birthday.set.result")
								.formatted(
										JsonHandle.getStringFromJsonKey(userID, "birthday.month_" + month),
										JsonHandle.getStringFromJsonKey(userID, "birthday.date_" + date))).queue();
			TimerHandle.setBirthday(userID, month, date);
		}

		private boolean isWrongDate(int month, int date)
		{
			return switch (month)
			{
				case 4, 6, 9, 11 -> date > 30; //小月不得超過30

				case 2 -> date > 29; //2月不得超過29

				default -> false; //因為前面已經檢查過31了 所以大月是不會錯的
			};
		}
	}
}